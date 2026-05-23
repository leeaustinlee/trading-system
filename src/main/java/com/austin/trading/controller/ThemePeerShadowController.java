package com.austin.trading.controller;

import com.austin.trading.entity.ThemePeerShadowCandidateEntity;
import com.austin.trading.repository.ThemePeerShadowCandidateRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/themes/peer-shadow")
public class ThemePeerShadowController {

    private final ThemePeerShadowCandidateRepository repository;

    public ThemePeerShadowController(ThemePeerShadowCandidateRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<PeerShadowResponse> list(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                         @RequestParam(required = false) String phase) {
        LocalDate targetDate = date == null ? LocalDate.now() : date;
        List<ThemePeerShadowCandidateEntity> rows = (phase == null || phase.isBlank())
                ? repository.findByTradingDateOrderBySourcePhaseAscLeaderSymbolAscShadowRankScoreDesc(targetDate)
                : repository.findByTradingDateAndSourcePhaseOrderByShadowRankScoreDesc(targetDate, phase);
        return rows.stream().map(PeerShadowResponse::from).toList();
    }

    @GetMapping("/by-leader")
    public List<PeerShadowResponse> byLeader(@RequestParam String symbol) {
        return repository.findByLeaderSymbolOrderByTradingDateDescShadowRankScoreDesc(symbol)
                .stream().map(PeerShadowResponse::from).toList();
    }

    @GetMapping("/replay")
    public ReplayResponse replay(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<PeerShadowResponse> rows = repository.findByTradingDateOrderBySourcePhaseAscLeaderSymbolAscShadowRankScoreDesc(date)
                .stream().map(PeerShadowResponse::from).toList();
        long tradableCount = rows.stream().filter(PeerShadowResponse::tradable).count();
        return new ReplayResponse(date, rows.size(), tradableCount, true,
                "peer_shadow_candidates are replay/observability only; they do not enter FinalDecision/BUY/SELL/ENTER or allowed_symbols",
                GovernanceTrace.fromRows(rows),
                rows);
    }

    public record ReplayResponse(LocalDate date,
                                 int peerShadowCount,
                                 long tradableCount,
                                 boolean shadowOnly,
                                 String safetyContract,
                                 GovernanceTrace themeGovernanceTrace,
                                 List<PeerShadowResponse> rows) {}

    public record GovernanceTrace(boolean hasPeerShadowAnalysis,
                                  boolean ignoredRetainedLeader,
                                  boolean ignoredTaxonomyGap,
                                  boolean violatesAllowedUniverseContract,
                                  List<String> mandatorySubmitSections,
                                  String safetyBoundary) {
        static GovernanceTrace fromRows(List<PeerShadowResponse> rows) {
            boolean hasRows = rows != null && !rows.isEmpty();
            boolean hasTaxonomyGap = rows != null && rows.stream().anyMatch(r ->
                    r.themeTag() == null
                            || r.themeTag().isBlank()
                            || "OTHER".equalsIgnoreCase(r.themeTag())
                            || "UNKNOWN".equalsIgnoreCase(r.themeTag())
                            || r.themeTag().contains("其他"));
            return new GovernanceTrace(
                    hasRows,
                    false,
                    !hasTaxonomyGap,
                    rows != null && rows.stream().anyMatch(PeerShadowResponse::tradable),
                    List.of("leadership_analysis", "divergence_analysis", "taxonomy_gap_analysis", "peer_shadow_analysis"),
                    "governance/shadow-only; peer shadow stays out of FinalDecision/BUY/SELL/ENTER/allowed_symbols"
            );
        }
    }

    public record PeerShadowResponse(
            LocalDate tradingDate,
            String sourcePhase,
            String leaderSymbol,
            String symbol,
            String stockName,
            String themeTag,
            String candidateRole,
            BigDecimal themeImportanceScore,
            BigDecimal tradableScore,
            BigDecimal shadowRankScore,
            boolean tradable,
            String rejectionReason,
            String evidenceJson
    ) {
        static PeerShadowResponse from(ThemePeerShadowCandidateEntity e) {
            return new PeerShadowResponse(
                    e.getTradingDate(),
                    e.getSourcePhase(),
                    e.getLeaderSymbol(),
                    e.getSymbol(),
                    e.getStockName(),
                    e.getThemeTag(),
                    e.getCandidateRole(),
                    e.getThemeImportanceScore(),
                    e.getTradableScore(),
                    e.getShadowRankScore(),
                    Boolean.TRUE.equals(e.getTradable()),
                    e.getRejectionReason(),
                    e.getEvidenceJson()
            );
        }
    }
}
