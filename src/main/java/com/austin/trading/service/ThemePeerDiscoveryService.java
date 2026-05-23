package com.austin.trading.service;

import com.austin.trading.dto.response.CandidateResponse;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeLeaderRetentionEntity;
import com.austin.trading.entity.ThemePeerShadowCandidateEntity;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemePeerShadowCandidateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * MVP-2B: deterministic, shadow-only peer discovery from retained leaders.
 *
 * This service writes observability/replay peer candidates only. It deliberately
 * does not change FinalDecisionEngine, BUY/SELL/ENTER, production candidate
 * ranking, allowed_symbols, or risk gates.
 */
@Service
public class ThemePeerDiscoveryService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
    private static final String SHADOW_REJECTION = "SHADOW_ONLY_NOT_TRADABLE_CANDIDATE; must_not_expand_allowed_symbols=true";

    private final ThemePeerShadowCandidateRepository shadowRepository;
    private final StockThemeMappingRepository mappingRepository;
    private final CandidateStockRepository candidateRepository;
    private final ObjectMapper objectMapper;

    public ThemePeerDiscoveryService(ThemePeerShadowCandidateRepository shadowRepository,
                                     StockThemeMappingRepository mappingRepository,
                                     CandidateStockRepository candidateRepository,
                                     ObjectMapper objectMapper) {
        this.shadowRepository = shadowRepository;
        this.mappingRepository = mappingRepository;
        this.candidateRepository = candidateRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public List<ThemePeerShadowCandidateEntity> discoverAndSave(LocalDate tradingDate,
                                                                 String sourcePhase,
                                                                 List<ThemeLeaderRetentionEntity> retainedLeaders) {
        if (tradingDate == null || sourcePhase == null || sourcePhase.isBlank() || retainedLeaders == null || retainedLeaders.isEmpty()) {
            return List.of();
        }
        Map<String, ThemePeerShadowCandidateEntity> result = new LinkedHashMap<>();
        for (ThemeLeaderRetentionEntity leader : retainedLeaders) {
            if (leader == null || blank(leader.getSymbol())) continue;
            for (ThemePeerShadowCandidateEntity peer : discoverForLeader(tradingDate, sourcePhase, leader)) {
                result.put(peer.getLeaderSymbol() + "::" + peer.getSymbol(), peer);
            }
        }
        return result.values().stream()
                .sorted(Comparator.comparing(ThemePeerShadowCandidateEntity::getShadowRankScore,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(ThemePeerShadowCandidateEntity::getSymbol))
                .toList();
    }

    @Transactional
    public List<ThemePeerShadowCandidateEntity> discoverAndSaveFromLeaderContexts(LocalDate tradingDate,
                                                                                   String sourcePhase,
                                                                                   List<ClaudeCodeRequestWriterService.LeaderContext> leaders) {
        if (leaders == null || leaders.isEmpty()) return List.of();
        List<ThemeLeaderRetentionEntity> entities = new ArrayList<>();
        for (ClaudeCodeRequestWriterService.LeaderContext leader : leaders) {
            if (leader == null || blank(leader.symbol())) continue;
            ThemeLeaderRetentionEntity entity = new ThemeLeaderRetentionEntity();
            entity.setTradingDate(tradingDate);
            entity.setSourcePhase("POSTMARKET");
            entity.setTargetPhase(sourcePhase);
            entity.setSymbol(leader.symbol().trim());
            entity.setStockName(leader.stockName());
            entity.setThemeTag(leader.themeTag());
            entity.setLeaderRank(leader.leaderRank());
            entity.setScore(null);
            entity.setLeaderTradable(leader.leaderTradable());
            entity.setRetentionReason(leader.retentionReason());
            entity.setUseFor(leader.useFor() == null ? "PEER_DISCOVERY" : String.join(",", leader.useFor()));
            entity.setActive(true);
            entities.add(entity);
        }
        return discoverAndSave(tradingDate, sourcePhase, entities);
    }

    @Transactional
    public List<ThemePeerShadowCandidateEntity> discoverAndSaveFromCandidates(LocalDate tradingDate,
                                                                               String sourcePhase,
                                                                               List<CandidateResponse> leaders) {
        if (leaders == null || leaders.isEmpty()) return List.of();
        List<ThemeLeaderRetentionEntity> entities = new ArrayList<>();
        int rank = 0;
        for (CandidateResponse leader : leaders) {
            if (leader == null || blank(leader.symbol())) continue;
            rank++;
            ThemeLeaderRetentionEntity entity = new ThemeLeaderRetentionEntity();
            entity.setTradingDate(tradingDate);
            entity.setSourcePhase(sourcePhase);
            entity.setTargetPhase(sourcePhase);
            entity.setSymbol(leader.symbol().trim());
            entity.setStockName(leader.stockName());
            entity.setThemeTag(leader.themeTag());
            entity.setLeaderRank(rank);
            entity.setScore(leader.score());
            entity.setLeaderTradable(false);
            entity.setRetentionReason("POSTMARKET super_strong_5 peer discovery shadow source");
            entity.setUseFor("PEER_DISCOVERY");
            entity.setActive(true);
            entities.add(entity);
        }
        return discoverAndSave(tradingDate, sourcePhase, entities);
    }

    @Transactional(readOnly = true)
    public List<ClaudeCodeRequestWriterService.PeerShadowContext> loadPeerShadowContexts(LocalDate tradingDate, String sourcePhase) {
        if (tradingDate == null || blank(sourcePhase)) return List.of();
        return toPeerShadowContexts(shadowRepository.findByTradingDateAndSourcePhaseOrderByShadowRankScoreDesc(tradingDate, sourcePhase));
    }

    public List<ClaudeCodeRequestWriterService.PeerShadowContext> toPeerShadowContexts(List<ThemePeerShadowCandidateEntity> peers) {
        if (peers == null || peers.isEmpty()) return List.of();
        return peers.stream()
                .filter(p -> p != null && !blank(p.getSymbol()))
                .map(p -> new ClaudeCodeRequestWriterService.PeerShadowContext(
                        p.getSymbol(),
                        p.getCandidateRole(),
                        p.getLeaderSymbol(),
                        p.getThemeTag(),
                        Boolean.TRUE.equals(p.getTradable()),
                        p.getShadowRankScore(),
                        evidenceSummary(p)
                ))
                .toList();
    }

    private List<ThemePeerShadowCandidateEntity> discoverForLeader(LocalDate tradingDate, String sourcePhase, ThemeLeaderRetentionEntity leader) {
        String theme = leader.getThemeTag();
        if (blank(theme)) return List.of();
        List<StockThemeMappingEntity> mappings = mappingRepository.findByThemeTagAndIsActiveTrue(theme.trim());
        if (mappings == null || mappings.isEmpty()) {
            mappings = List.of(syntheticLeaderMapping(leader));
        }
        Map<String, ThemePeerShadowCandidateEntity> rows = new LinkedHashMap<>();
        rows.put(leader.getSymbol().trim(), upsert(buildRow(tradingDate, sourcePhase, leader, syntheticLeaderMapping(leader), Optional.empty(), true)));
        for (StockThemeMappingEntity mapping : mappings) {
            if (mapping == null || blank(mapping.getSymbol())) continue;
            Optional<CandidateStockEntity> candidate = candidateRepository.findByTradingDateAndSymbol(tradingDate, mapping.getSymbol().trim());
            ThemePeerShadowCandidateEntity row = buildRow(tradingDate, sourcePhase, leader, mapping, candidate, false);
            rows.putIfAbsent(row.getSymbol(), upsert(row));
        }
        return new ArrayList<>(rows.values());
    }

    private ThemePeerShadowCandidateEntity buildRow(LocalDate date,
                                                    String phase,
                                                    ThemeLeaderRetentionEntity leader,
                                                    StockThemeMappingEntity mapping,
                                                    Optional<CandidateStockEntity> candidate,
                                                    boolean leaderSelf) {
        String symbol = mapping.getSymbol().trim();
        ThemePeerShadowCandidateEntity entity = shadowRepository
                .findByTradingDateAndSourcePhaseAndLeaderSymbolAndSymbol(date, phase, leader.getSymbol().trim(), symbol)
                .orElseGet(ThemePeerShadowCandidateEntity::new);
        String role = roleFor(leader, mapping, candidate, leaderSelf);
        BigDecimal themeScore = score(themeRelevance(leader, mapping, leaderSelf));
        BigDecimal tradableScore = ZERO;
        BigDecimal shadowScore = score(shadowScore(leader, mapping, candidate, leaderSelf, role));

        entity.setTradingDate(date);
        entity.setSourcePhase(phase);
        entity.setLeaderSymbol(leader.getSymbol().trim());
        entity.setSymbol(symbol);
        entity.setStockName(firstNonBlank(mapping.getStockName(), candidate.map(CandidateStockEntity::getStockName).orElse(null)));
        entity.setThemeTag(firstNonBlank(mapping.getThemeTag(), leader.getThemeTag()));
        entity.setCandidateRole(role);
        entity.setThemeImportanceScore(themeScore);
        entity.setTradableScore(tradableScore);
        entity.setShadowRankScore(shadowScore);
        entity.setTradable(false);
        entity.setRejectionReason(leaderSelf
                ? "SHADOW_LEADER_CONTEXT_ONLY; leader_tradable=false; must_not_expand_allowed_symbols=true"
                : SHADOW_REJECTION);
        entity.setEvidenceJson(evidenceJson(leader, mapping, candidate, leaderSelf, role));
        return entity;
    }

    private ThemePeerShadowCandidateEntity upsert(ThemePeerShadowCandidateEntity entity) {
        return shadowRepository.save(entity);
    }

    private String roleFor(ThemeLeaderRetentionEntity leader, StockThemeMappingEntity mapping,
                           Optional<CandidateStockEntity> candidate, boolean leaderSelf) {
        if (leaderSelf || leader.getSymbol().equals(mapping.getSymbol())) return "THEME_LEADER";
        String source = upper(mapping.getSource());
        String sub = upper(mapping.getSubTheme());
        String payload = candidate.map(CandidateStockEntity::getPayloadJson).orElse("");
        if (source.contains("WATCH_ONLY") || payload.contains("limitUp") || payload.contains("流動性差") || payload.contains("高風險")) {
            return "WATCH_ONLY";
        }
        if (source.contains("CHANNEL") || source.contains("DISTRIBUTOR") || sub.contains("通路") || sub.contains("代理")) {
            return "CHANNEL_DISTRIBUTOR";
        }
        if (source.contains("SUPPLIER") || sub.contains("供應")) return "SUPPLIER";
        if (source.contains("SENTIMENT")) return "SENTIMENT_STOCK";
        BigDecimal score = candidate.map(CandidateStockEntity::getScore).orElse(null);
        if (score != null && score.compareTo(new BigDecimal("8.50")) >= 0) return "SECOND_LEADER";
        if (score != null && score.compareTo(new BigDecimal("8.10")) >= 0) return "SENTIMENT_STOCK";
        return "LOW_BASE_FOLLOWER";
    }

    private double themeRelevance(ThemeLeaderRetentionEntity leader, StockThemeMappingEntity mapping, boolean leaderSelf) {
        double score = leaderSelf ? 4.0 : 3.0;
        if (!blank(leader.getThemeTag()) && leader.getThemeTag().equals(mapping.getThemeTag())) score += 2.0;
        if (!blank(mapping.getSubTheme())) score += 1.0;
        if (!blank(mapping.getSource())) score += 0.5;
        return score;
    }

    private double shadowScore(ThemeLeaderRetentionEntity leader, StockThemeMappingEntity mapping,
                               Optional<CandidateStockEntity> candidate, boolean leaderSelf, String role) {
        double score = themeRelevance(leader, mapping, leaderSelf);
        BigDecimal candidateScore = candidate.map(CandidateStockEntity::getScore).orElse(null);
        if (candidateScore != null) score += Math.min(2.0, Math.max(0.0, candidateScore.doubleValue() - 7.0));
        String payload = candidate.map(CandidateStockEntity::getPayloadJson).orElse("");
        if (payload.contains("volumeExpansion") || payload.contains("爆量")) score += 1.0;
        if (payload.contains("continuation") || payload.contains("續強")) score += 0.8;
        if (candidate.isPresent()) score += 0.7; // hot stock / candidate overlap
        if ("CHANNEL_DISTRIBUTOR".equals(role)) score += 0.4;
        if ("SECOND_LEADER".equals(role)) score += 0.6;
        if ("SENTIMENT_STOCK".equals(role) || "WATCH_ONLY".equals(role) || payload.contains("limitUp")) score -= 0.8;
        return score;
    }

    private String evidenceJson(ThemeLeaderRetentionEntity leader, StockThemeMappingEntity mapping,
                                Optional<CandidateStockEntity> candidate, boolean leaderSelf, String role) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("source", "theme_peer_discovery_v1");
            root.put("shadowOnly", true);
            root.put("mustNotAffectFinalDecisionEngine", true);
            root.put("mustNotExpandAllowedSymbols", true);
            root.put("leaderSymbol", leader.getSymbol());
            root.put("sameThemeTag", !blank(leader.getThemeTag()) && leader.getThemeTag().equals(mapping.getThemeTag()));
            root.put("sameSubTheme", !blank(mapping.getSubTheme()));
            root.put("stockThemeMappingRelation", !blank(mapping.getSource()) ? mapping.getSource() : "theme_tag");
            root.put("hotStockOverlap", candidate.isPresent());
            root.put("candidateRole", role);
            root.put("leaderSelf", leaderSelf);
            if (candidate.map(CandidateStockEntity::getPayloadJson).orElse("").contains("volumeExpansion")) root.put("volumeExpansion", true);
            if (candidate.map(CandidateStockEntity::getPayloadJson).orElse("").contains("continuation")) root.put("continuation", true);
            root.put("evidenceSummary", evidenceSummaryText(mapping, candidate, role));
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"source\":\"theme_peer_discovery_v1\",\"shadowOnly\":true,\"mustNotExpandAllowedSymbols\":true}";
        }
    }

    private String evidenceSummary(ThemePeerShadowCandidateEntity peer) {
        if (peer.getEvidenceJson() == null) return "same theme peer shadow; not tradable candidate";
        try {
            return objectMapper.readTree(peer.getEvidenceJson()).path("evidenceSummary").asText("same theme peer shadow; not tradable candidate");
        } catch (Exception ignored) {
            return "same theme peer shadow; not tradable candidate";
        }
    }

    private String evidenceSummaryText(StockThemeMappingEntity mapping, Optional<CandidateStockEntity> candidate, String role) {
        List<String> parts = new ArrayList<>();
        parts.add("same themeTag");
        if (!blank(mapping.getSubTheme())) parts.add("same/related subTheme=" + mapping.getSubTheme());
        if (!blank(mapping.getSource())) parts.add("mapping=" + mapping.getSource());
        if (candidate.isPresent()) parts.add("hot stock/candidate overlap");
        parts.add("role=" + role);
        parts.add("shadow-only, not tradable candidate");
        return String.join("; ", parts);
    }

    private StockThemeMappingEntity syntheticLeaderMapping(ThemeLeaderRetentionEntity leader) {
        StockThemeMappingEntity mapping = new StockThemeMappingEntity();
        mapping.setSymbol(leader.getSymbol());
        mapping.setStockName(leader.getStockName());
        mapping.setThemeTag(leader.getThemeTag());
        mapping.setSubTheme("THEME_LEADER");
        mapping.setSource("THEME_LEADER");
        mapping.setIsActive(true);
        return mapping;
    }

    private BigDecimal score(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String upper(String s) { return s == null ? "" : s.toUpperCase(Locale.ROOT); }
    private static String firstNonBlank(String a, String b) { return !blank(a) ? a : b; }
}
