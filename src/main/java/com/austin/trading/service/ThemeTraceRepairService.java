package com.austin.trading.service;

import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PaperTradeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ThemeTraceRepairService {

    private final CandidateForwardTrackingRepository forwardTrackingRepository;
    private final CandidateStockRepository candidateStockRepository;
    private final PaperTradeRepository paperTradeRepository;

    public ThemeTraceRepairService(CandidateForwardTrackingRepository forwardTrackingRepository,
                                   CandidateStockRepository candidateStockRepository,
                                   PaperTradeRepository paperTradeRepository) {
        this.forwardTrackingRepository = forwardTrackingRepository;
        this.candidateStockRepository = candidateStockRepository;
        this.paperTradeRepository = paperTradeRepository;
    }

    @Transactional
    public Map<String, Object> repair(int days) {
        int window = days > 0 ? days : 60;
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(window);
        int repaired = 0;
        int skipped = 0;
        List<Map<String, Object>> dataGaps = new ArrayList<>();

        List<CandidateForwardTrackingEntity> rows = forwardTrackingRepository.findByTradingDateBetween(start, end);
        for (CandidateForwardTrackingEntity row : rows) {
            CandidateStockEntity candidate = resolveCandidate(row.getTradingDate(), row.getStockId());
            if (candidate == null) {
                skipped++;
                addGap(dataGaps, row.getStockId(), row.getTradingDate(), "DATA_GAP: no same-day candidate_stock match");
                continue;
            }
            if (!hasUsableTheme(candidate)) {
                skipped++;
                addGap(dataGaps, row.getStockId(), row.getTradingDate(), "DATA_GAP: matched candidate_stock has no mainstream theme");
                continue;
            }
            boolean changed = false;
            if (isThemeLost(row.getThemeTag(), row.getThemeReason())) {
                row.setThemeTag(candidate.getThemeTag());
                changed = true;
            }
            if (isBlank(row.getThemeReason()) && !isBlank(candidate.getReason())) {
                row.setThemeReason(candidate.getReason());
                changed = true;
            }
            if (!Objects.equals(row.getSourceCandidateId(), candidate.getId())) {
                row.setSourceCandidateId(candidate.getId());
                changed = true;
            }
            if (changed) {
                forwardTrackingRepository.save(row);
                repaired++;
            } else {
                skipped++;
            }
        }

        for (PaperTradeEntity trade : paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(start, end)) {
            if (!trade.isShadow() || !isThemeLost(trade.getThemeTag(), null)) continue;
            CandidateStockEntity candidate = resolveCandidate(trade.getEntryDate(), trade.getSymbol());
            if (candidate == null || !hasUsableTheme(candidate)) continue;
            trade.setThemeTag(candidate.getThemeTag());
            paperTradeRepository.save(trade);
            repaired++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("repairedRows", repaired);
        out.put("skippedRows", skipped);
        out.put("dataGaps", dataGaps);
        out.put("from", start);
        out.put("to", end);
        return out;
    }

    private CandidateStockEntity resolveCandidate(LocalDate date, String symbol) {
        if (date == null || isBlank(symbol)) return null;
        return candidateStockRepository.findByTradingDateAndSymbol(date, symbol).orElse(null);
    }

    private boolean hasUsableTheme(CandidateStockEntity candidate) {
        String normalized = MainstreamThemeNormalizer.normalize(candidate.getThemeTag(), candidate.getReason());
        return !"UNKNOWN".equals(normalized) && !"OTHER".equals(normalized);
    }

    private boolean isThemeLost(String themeTag, String reason) {
        String normalized = MainstreamThemeNormalizer.normalize(themeTag, reason);
        return "UNKNOWN".equals(normalized) || "OTHER".equals(normalized);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void addGap(List<Map<String, Object>> gaps, String symbol, LocalDate date, String reason) {
        if (gaps.size() >= 20) return;
        Map<String, Object> gap = new LinkedHashMap<>();
        gap.put("symbol", symbol == null ? "" : symbol);
        gap.put("date", date);
        gap.put("reason", reason);
        gaps.add(gap);
    }
}
