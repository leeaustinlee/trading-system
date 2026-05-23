package com.austin.trading.service;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.ThemeLeaderRetentionEntity;
import com.austin.trading.repository.ThemeLeaderRetentionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shadow-only retention for POSTMARKET super_strong_5 leaders.
 *
 * Retained leaders are not candidate ranking input. They only keep leadership
 * names visible in Claude research context across T86 / PREMARKET / OPENING.
 */
@Service
public class ThemeLeaderRetentionService {

    public static final List<String> DEFAULT_TARGET_PHASES = List.of("T86_TOMORROW", "PREMARKET", "OPENING");
    private static final String SOURCE_PHASE = "POSTMARKET";
    private static final String RETENTION_REASON = "POSTMARKET super_strong_5 retained for next-phase leadership validation";
    private static final List<String> USE_FOR = List.of("MARKET_LEADERSHIP", "THEME_VALIDATION", "PEER_DISCOVERY");

    private final ThemeLeaderRetentionRepository repository;
    private final ObjectMapper objectMapper;

    public ThemeLeaderRetentionService(ThemeLeaderRetentionRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public int retainPostmarketSuperStrong(LocalDate tradingDate, List<CandidateResponse> superStrongLeaders) {
        if (tradingDate == null || superStrongLeaders == null || superStrongLeaders.isEmpty()) return 0;
        int saved = 0;
        int rank = 0;
        for (CandidateResponse leader : superStrongLeaders) {
            if (leader == null || leader.symbol() == null || leader.symbol().isBlank()) continue;
            rank++;
            for (String targetPhase : DEFAULT_TARGET_PHASES) {
                ThemeLeaderRetentionEntity entity = repository
                        .findByTradingDateAndTargetPhaseAndSymbol(tradingDate, targetPhase, leader.symbol().trim())
                        .orElseGet(ThemeLeaderRetentionEntity::new);
                entity.setTradingDate(tradingDate);
                entity.setSourcePhase(SOURCE_PHASE);
                entity.setTargetPhase(targetPhase);
                entity.setSymbol(leader.symbol().trim());
                entity.setStockName(leader.stockName());
                entity.setThemeTag(leader.themeTag());
                entity.setLeaderRank(rank);
                entity.setScore(leader.score());
                entity.setLeaderTradable(false);
                entity.setRetentionReason(RETENTION_REASON);
                entity.setUseFor(String.join(",", USE_FOR));
                entity.setActive(true);
                entity.setPayloadJson(buildPayload(leader, targetPhase));
                repository.save(entity);
                saved++;
            }
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ClaudeCodeRequestWriterService.LeaderContext> loadLeaderContexts(LocalDate targetDate, String targetPhase) {
        if (targetDate == null || targetPhase == null || targetPhase.isBlank()) return List.of();
        List<ThemeLeaderRetentionEntity> rows = repository
                .findByTargetPhaseAndActiveTrueAndTradingDateLessThanEqualOrderByTradingDateDescLeaderRankAsc(targetPhase, targetDate);
        if (rows.isEmpty()) return List.of();

        LocalDate latestSourceDate = rows.get(0).getTradingDate();
        Map<String, ClaudeCodeRequestWriterService.LeaderContext> unique = new LinkedHashMap<>();
        for (ThemeLeaderRetentionEntity row : rows) {
            if (!latestSourceDate.equals(row.getTradingDate())) break;
            if (row.getSymbol() == null || row.getSymbol().isBlank()) continue;
            unique.putIfAbsent(row.getSymbol(), new ClaudeCodeRequestWriterService.LeaderContext(
                    row.getSymbol(),
                    row.getStockName(),
                    row.getThemeTag(),
                    row.getLeaderRank(),
                    Boolean.TRUE.equals(row.getLeaderTradable()),
                    row.getRetentionReason(),
                    splitUseFor(row.getUseFor())
            ));
        }
        return new ArrayList<>(unique.values());
    }

    private List<String> splitUseFor(String useFor) {
        if (useFor == null || useFor.isBlank()) return USE_FOR;
        List<String> values = new ArrayList<>();
        for (String part : useFor.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) values.add(trimmed);
        }
        return values.isEmpty() ? USE_FOR : values;
    }

    private String buildPayload(CandidateResponse leader, String targetPhase) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("source", "theme_leader_retention_v1");
            root.put("sourcePhase", SOURCE_PHASE);
            root.put("targetPhase", targetPhase);
            root.put("shadowOnly", true);
            root.put("leaderTradable", false);
            root.put("mustNotAffectFinalDecisionEngine", true);
            root.put("mustNotBecomeEnterCandidate", true);
            root.put("symbol", leader.symbol());
            if (leader.stockName() != null) root.put("stockName", leader.stockName());
            if (leader.themeTag() != null) root.put("themeTag", leader.themeTag());
            if (leader.score() != null) root.put("score", leader.score());
            root.put("retentionReason", RETENTION_REASON);
            var useFor = root.putArray("useFor");
            USE_FOR.forEach(useFor::add);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"source\":\"theme_leader_retention_v1\",\"shadowOnly\":true,\"mustNotAffectFinalDecisionEngine\":true}";
        }
    }
}
