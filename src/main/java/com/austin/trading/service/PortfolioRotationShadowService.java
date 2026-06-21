package com.austin.trading.service;

import com.austin.trading.dto.response.PortfolioRotationShadowResponse;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PositionRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PortfolioRotationShadowService {
    private static final BigDecimal ROTATE_THRESHOLD = new BigDecimal("8");
    private static final BigDecimal REDUCE_THRESHOLD = new BigDecimal("5");

    private final CandidateStockRepository candidateStockRepository;
    private final PositionRepository positionRepository;
    private final CandidateForwardTrackingRepository forwardTrackingRepository;

    public PortfolioRotationShadowService(CandidateStockRepository candidateStockRepository,
                                          PositionRepository positionRepository,
                                          CandidateForwardTrackingRepository forwardTrackingRepository) {
        this.candidateStockRepository = candidateStockRepository;
        this.positionRepository = positionRepository;
        this.forwardTrackingRepository = forwardTrackingRepository;
    }

    public PortfolioRotationShadowResponse report(int days) {
        int requestedDays = Math.max(1, days);
        LocalDate end = candidateStockRepository.findTopByOrderByTradingDateDesc()
                .map(CandidateStockEntity::getTradingDate)
                .orElse(LocalDate.now());
        LocalDate start = end.minusDays(requestedDays - 1L);

        List<PositionEntity> openPositions = positionRepository.findByStatus("OPEN");
        List<CandidateStockEntity> candidates = candidateStockRepository
                .findByTradingDateBetweenOrderByTradingDateDescScoreDesc(start, end);
        List<CandidateForwardTrackingEntity> forward = forwardTrackingRepository == null
                ? List.of()
                : forwardTrackingRepository.findByTradingDateBetween(start, end);

        List<String> dataGaps = dataGaps(openPositions, candidates, forward);
        if (openPositions.isEmpty() || candidates.isEmpty()) {
            return PortfolioRotationShadowResponse.of(
                    requestedDays, start, end, openPositions.size(), candidates.size(), List.of(), dataGaps);
        }

        Map<String, CandidateStockEntity> latestCandidateBySymbol = candidates.stream()
                .filter(c -> c.getSymbol() != null)
                .collect(Collectors.toMap(CandidateStockEntity::getSymbol, Function.identity(), (a, b) -> a));
        CandidateStockEntity bestCandidate = candidates.stream()
                .filter(c -> c.getSymbol() != null)
                .filter(c -> openPositions.stream().noneMatch(p -> c.getSymbol().equals(p.getSymbol())))
                .max(Comparator.comparing(this::scoreOrZero))
                .orElseGet(() -> candidates.stream().max(Comparator.comparing(this::scoreOrZero)).orElse(null));
        PositionEntity weakestHolding = openPositions.stream()
                .min(Comparator.comparing(p -> holdingScore(p, latestCandidateBySymbol)))
                .orElse(null);

        if (bestCandidate == null || weakestHolding == null) {
            return PortfolioRotationShadowResponse.of(
                    requestedDays, start, end, openPositions.size(), candidates.size(), List.of(), dataGaps);
        }

        BigDecimal candidateScore = scoreOrZero(bestCandidate);
        BigDecimal weakestScore = holdingScore(weakestHolding, latestCandidateBySymbol);
        BigDecimal lifecycleDifferential = lifecycleDifferential(bestCandidate, latestCandidateBySymbol.get(weakestHolding.getSymbol()));
        Optional<BigDecimal> opportunityDelta = opportunityDelta(bestCandidate, weakestHolding, forward);
        BigDecimal scoreGap = candidateScore.subtract(weakestScore);
        String shadowAction = shadowAction(scoreGap, lifecycleDifferential);

        var item = new PortfolioRotationShadowResponse.Item(
                bestCandidate.getTradingDate(),
                bestCandidate.getSymbol(),
                bestCandidate.getStockName(),
                bestCandidate.getThemeTag(),
                candidateScore,
                weakestHolding.getSymbol(),
                weakestHolding.getStockName(),
                latestCandidateBySymbol.get(weakestHolding.getSymbol()) == null
                        ? null : latestCandidateBySymbol.get(weakestHolding.getSymbol()).getThemeTag(),
                weakestScore,
                lifecycleDifferential,
                opportunityDelta.orElse(null),
                opportunityDelta.isEmpty(),
                shadowAction,
                "SHADOW_ONLY rotation review; advisoryOnly=true; doesNotAffectBuySell=true; "
                        + "doesNotMutatePositions=true; doesNotAffectRiskGate=true; scoreGap=" + scoreGap
        );
        return PortfolioRotationShadowResponse.of(
                requestedDays, start, end, openPositions.size(), candidates.size(), List.of(item), dataGaps);
    }

    private List<String> dataGaps(List<PositionEntity> openPositions,
                                  List<CandidateStockEntity> candidates,
                                  List<CandidateForwardTrackingEntity> forward) {
        java.util.ArrayList<String> gaps = new java.util.ArrayList<>();
        if (openPositions.isEmpty()) gaps.add("NO_OPEN_POSITIONS");
        if (candidates.isEmpty()) gaps.add("NO_CANDIDATES_IN_REQUESTED_WINDOW");
        if (forward.isEmpty()) gaps.add("OPPORTUNITY_DELTA_DATA_GAP:candidate_forward_tracking");
        return List.copyOf(gaps);
    }

    private BigDecimal holdingScore(PositionEntity position, Map<String, CandidateStockEntity> latestCandidateBySymbol) {
        CandidateStockEntity candidate = latestCandidateBySymbol.get(position.getSymbol());
        if (candidate != null && candidate.getScore() != null) return candidate.getScore();
        return BigDecimal.ZERO;
    }

    private BigDecimal scoreOrZero(CandidateStockEntity candidate) {
        if (candidate == null || candidate.getScore() == null) return BigDecimal.ZERO;
        return candidate.getScore();
    }

    private BigDecimal lifecycleDifferential(CandidateStockEntity candidate, CandidateStockEntity holdingCandidate) {
        BigDecimal candidateLifecycle = candidate.getThemeImportanceScore() != null
                ? candidate.getThemeImportanceScore() : BigDecimal.ZERO;
        BigDecimal holdingLifecycle = holdingCandidate != null && holdingCandidate.getThemeImportanceScore() != null
                ? holdingCandidate.getThemeImportanceScore() : BigDecimal.ZERO;
        return candidateLifecycle.subtract(holdingLifecycle);
    }

    private Optional<BigDecimal> opportunityDelta(CandidateStockEntity candidate,
                                                  PositionEntity holding,
                                                  List<CandidateForwardTrackingEntity> forward) {
        Optional<BigDecimal> candidateReturn = latestForwardReturn(forward, candidate.getSymbol());
        Optional<BigDecimal> holdingReturn = latestForwardReturn(forward, holding.getSymbol());
        if (candidateReturn.isPresent() && holdingReturn.isPresent()) {
            return Optional.of(candidateReturn.get().subtract(holdingReturn.get()));
        }
        return Optional.empty();
    }

    private Optional<BigDecimal> latestForwardReturn(List<CandidateForwardTrackingEntity> forward, String symbol) {
        return forward.stream()
                .filter(f -> Objects.equals(symbol, f.getStockId()))
                .sorted(Comparator.comparing(CandidateForwardTrackingEntity::getTradingDate,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(f -> f.getT10CloseReturnPct() != null ? f.getT10CloseReturnPct() : f.getT5CloseReturnPct())
                .filter(Objects::nonNull)
                .findFirst();
    }

    private String shadowAction(BigDecimal scoreGap, BigDecimal lifecycleDifferential) {
        if (scoreGap.compareTo(ROTATE_THRESHOLD) >= 0 && lifecycleDifferential.compareTo(BigDecimal.ZERO) >= 0) {
            return "SHADOW_ROTATE";
        }
        if (scoreGap.compareTo(REDUCE_THRESHOLD) >= 0) {
            return "SHADOW_REDUCE";
        }
        return "HOLD";
    }
}
