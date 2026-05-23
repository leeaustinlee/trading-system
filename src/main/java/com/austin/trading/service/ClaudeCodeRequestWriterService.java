package com.austin.trading.service;

import com.austin.trading.config.AiClaudeConfig;
import com.austin.trading.dto.response.CapitalSummaryResponse;
import com.austin.trading.dto.response.PositionResponse;
import com.austin.trading.entity.CapitalConfigEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Claude Code 研究請求寫入服務。
 * <p>
 * 無 API Key 模式下的核心橋梁：
 * Java 排程收集完市場資料後，呼叫此服務將「研究請求」寫成 JSON 檔案，
 * 由 Claude Code 排程 Agent 讀取後執行深度研究，再寫回 claude-research-latest.md。
 * </p>
 *
 * <pre>
 * 流程：
 *   PremarketDataPrepJob (08:10) → writeRequest("PREMARKET", ...)
 *   → claude-research-request.json
 *   → Claude Code 排程 Agent (08:20) 讀取並分析
 *   → claude-research-latest.md
 *   → PremarketNotifyJob (08:30) / Codex 讀取使用
 * </pre>
 *
 * <p>
 * 設定路徑（application.yml）：
 * <pre>
 *   trading.ai.claude.request-output-path: "D:/ai/stock/claude-research-request.json"
 * </pre>
 * 若路徑未設定，此服務的所有呼叫將靜默略過（不影響主流程）。
 * </p>
 */
@Service
public class ClaudeCodeRequestWriterService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeRequestWriterService.class);
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 固定規則檔路徑（Claude Code Agent 必讀）*/
    private static final List<String> RULES_FILES = List.of(
            "D:/ai/stock/AI_RULES_INDEX.md",
            "D:/ai/stock/dual-ai-workflow.md",
            "D:/ai/stock/market-data-protocol.md",
            "D:/ai/stock/market-snapshot.json",
            "D:/ai/stock/capital-summary.md",
            "D:/ai/stock/trade-decision-engine.md",
            "D:/ai/stock/market-gate-self-optimization-engine.md"
    );

    /** v2.5：Claude 研究輸出契約（提醒必讀）*/
    private static final String CONTRACT_FILE = "D:/ai/stock/claude-research-contract.md";
    private static final String VALIDATOR_PY  = "D:/ai/stock/validate-claude-submit.py";
    private static final String VALIDATOR_MJS = "D:/ai/stock/validate-claude-submit.mjs";
    private static final String PROMPT_REMINDER =
            "【契約必讀】執行前務必讀 " + CONTRACT_FILE
            + "；rename .tmp → .json 前必跑 "
            + VALIDATOR_PY + "（或 " + VALIDATOR_MJS + "）本地驗證；"
            + "scores/thesis keys 必須 ⊆ allowed_symbols，違反 Java 會 400。";

    private final AiClaudeConfig config;
    private final ObjectMapper   objectMapper;
    /** v2.6：Live capital + positions context（避免 capital-summary.md 過舊）。可選注入。 */
    private final CapitalService  capitalService;
    private final PositionService positionService;
    private final PortfolioHealthV2Service portfolioHealthV2Service;

    @Autowired
    public ClaudeCodeRequestWriterService(AiClaudeConfig config,
                                          ObjectMapper objectMapper,
                                          CapitalService capitalService,
                                          PositionService positionService,
                                          PortfolioHealthV2Service portfolioHealthV2Service) {
        this.config          = config;
        this.objectMapper    = objectMapper;
        this.capitalService  = capitalService;
        this.positionService = positionService;
        this.portfolioHealthV2Service = portfolioHealthV2Service;
    }

    /** 向下相容建構式：未提供 health-v2 service，position_health_v2 會以 warning 標示。 */
    public ClaudeCodeRequestWriterService(AiClaudeConfig config,
                                          ObjectMapper objectMapper,
                                          CapitalService capitalService,
                                          PositionService positionService) {
        this(config, objectMapper, capitalService, positionService, null);
    }

    /** 向下相容建構式：未提供 capital/position service，capital_context 會以 warning 標示。 */
    public ClaudeCodeRequestWriterService(AiClaudeConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, null, null);
    }

    /**
     * 舊版 API：不帶 taskId。為向下相容保留。
     * <b>新程式碼請改用帶 taskId 的 overload。</b>
     */
    public boolean writeRequest(
            String type,
            LocalDate tradingDate,
            List<String> candidateSymbols,
            String contextPayload
    ) {
        return writeRequest(null, type, tradingDate, candidateSymbols, contextPayload);
    }

    /**
     * v2.5：寫出研究請求 JSON。新版契約加入 <b>taskId + allowed_symbols</b>，
     * 讓 Claude Code Agent 有絕對、可驗證的 score universe，避免跨時段沿用上一輪 symbols。
     *
     * <pre>{@code
     * {
     *   "taskId": 8,
     *   "taskType": "OPENING",
     *   "type": "OPENING",                    // 舊鍵名保留，= taskType
     *   "trading_date": "2026-04-21",
     *   "candidates": ["3189","4958",...],     // 舊鍵名保留
     *   "allowed_symbols": ["3189","4958",...], // v2.5 明確契約：score/thesis keys 必須 ⊆ 此 set
     *   "contract_note": "scores.keys 與 thesis.keys 必須是 allowed_symbols 子集；其他 symbol 一律丟棄",
     *   "market_context": "...",
     *   "rules_files": [...],
     *   "output_path": ".../claude-research-latest.md",
     *   "submit_filename_hint": "claude-OPENING-2026-04-21-0920-task-8.json"
     * }
     * }</pre>
     */
    public boolean writeRequest(
            Long taskId,
            String type,
            LocalDate tradingDate,
            List<String> candidateSymbols,
            String contextPayload
    ) {
        return writeRequest(taskId, type, tradingDate, candidateSymbols, List.of(), contextPayload);
    }

    /**
     * MVP-2A：leader/tradable split. tradableCandidateSymbols are still the only
     * ENTER/scoring candidates; leadershipSymbols are read-only market-leader
     * context and are added to allowed_symbols only so Claude can discuss them.
     */
    public boolean writeRequest(
            Long taskId,
            String type,
            LocalDate tradingDate,
            List<String> tradableCandidateSymbols,
            List<LeaderContext> leadershipContexts,
            String contextPayload
    ) {
        return writeRequest(taskId, type, tradingDate, tradableCandidateSymbols, leadershipContexts, List.of(), contextPayload);
    }

    /**
     * MVP-2B：peer_shadow_candidates are context-only. They are intentionally
     * not added to candidates/tradable_candidate_symbols/allowed_symbols.
     */
    public boolean writeRequest(
            Long taskId,
            String type,
            LocalDate tradingDate,
            List<String> tradableCandidateSymbols,
            List<LeaderContext> leadershipContexts,
            List<PeerShadowContext> peerShadowContexts,
            String contextPayload
    ) {
        String path = config.getRequestOutputPath();
        if (path == null || path.isBlank()) {
            log.debug("[ClaudeCodeRequestWriter] request-output-path not set, skip.");
            return false;
        }

        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("requested_at", LocalDateTime.now().format(DT_FMT));

            // v2.5 明確 routing
            if (taskId != null) root.put("taskId", taskId);
            if (type != null)   root.put("taskType", type);
            root.put("type", type);                           // 舊欄位保留
            root.put("trading_date", tradingDate.toString());
            root.put("tradingDate", tradingDate.toString()); // v2.7：camelCase 供 prompt / script 直接取用

            // MVP-2A leader/tradable split：candidates/tradable_candidate_symbols are scoring/ENTER candidates only.
            // leadership_symbols are read-only market leadership context. allowed_symbols is the union for Claude research scope.
            Set<String> allowedUnion = new LinkedHashSet<>();
            ArrayNode candidates = root.putArray("candidates");
            ArrayNode tradable = root.putArray("tradable_candidate_symbols");
            if (tradableCandidateSymbols != null) {
                for (String s : tradableCandidateSymbols) {
                    if (s == null || s.isBlank()) continue;
                    String symbol = s.trim();
                    candidates.add(symbol);
                    tradable.add(symbol);
                    allowedUnion.add(symbol);
                }
            }

            ArrayNode leadershipSymbols = root.putArray("leadership_symbols");
            ArrayNode leadershipContext = root.putArray("leadership_context");
            if (leadershipContexts != null) {
                for (LeaderContext leader : leadershipContexts) {
                    if (leader == null || leader.symbol() == null || leader.symbol().isBlank()) continue;
                    String symbol = leader.symbol().trim();
                    leadershipSymbols.add(symbol);
                    allowedUnion.add(symbol);
                    ObjectNode item = leadershipContext.addObject();
                    item.put("symbol", symbol);
                    if (leader.stockName() != null) item.put("stockName", leader.stockName());
                    if (leader.themeTag() != null) item.put("themeTag", leader.themeTag());
                    if (leader.leaderRank() != null) item.put("leaderRank", leader.leaderRank());
                    item.put("leader_tradable", leader.leaderTradable());
                    item.put("retention_reason", leader.retentionReason());
                    ArrayNode useFor = item.putArray("use_for");
                    if (leader.useFor() != null) leader.useFor().forEach(useFor::add);
                }
            }
            ArrayNode peerShadow = root.putArray("peer_shadow_candidates");
            Set<String> emittedPeerShadowSymbols = new LinkedHashSet<>();
            if (peerShadowContexts != null) {
                for (PeerShadowContext peer : peerShadowContexts) {
                    if (peer == null || peer.symbol() == null || peer.symbol().isBlank()) continue;
                    String symbol = peer.symbol().trim();
                    if (allowedUnion.contains(symbol) || !emittedPeerShadowSymbols.add(symbol)) continue;
                    ObjectNode item = peerShadow.addObject();
                    item.put("symbol", symbol);
                    if (peer.role() != null) item.put("role", peer.role());
                    if (peer.leaderSymbol() != null) item.put("leader_symbol", peer.leaderSymbol());
                    if (peer.themeTag() != null) item.put("theme_tag", peer.themeTag());
                    item.put("tradable", peer.tradable());
                    if (peer.shadowRankScore() != null) item.put("shadow_rank_score", peer.shadowRankScore());
                    if (peer.evidenceSummary() != null) item.put("evidence_summary", peer.evidenceSummary());
                }
            }
            root.put("peer_shadow_tradable_false_allowed", true);
            root.put("peer_shadow_contract",
                    "peer_shadow_candidates are not tradable candidates; they must not enter ENTER/FinalDecision/ranking and must_not_expand_allowed_symbols=true keeps them disjoint from allowed_symbols/tradable_candidate_symbols.");

            boolean hasLeadership = leadershipContext.size() > 0 || leadershipSymbols.size() > 0;
            boolean hasPeerShadow = peerShadow.size() > 0;
            boolean governanceRequired = hasLeadership || hasPeerShadow;
            ObjectNode governance = root.putObject("prompt_governance_contract");
            governance.put("version", "MVP-3");
            governance.put("governance_required", governanceRequired);
            ArrayNode mandatorySections = governance.putArray("mandatory_sections");
            mandatorySections.add("leadership_analysis");
            mandatorySections.add("divergence_analysis");
            mandatorySections.add("taxonomy_gap_analysis");
            mandatorySections.add("peer_shadow_analysis");
            governance.put("outside_allowed_universe_policy", "OUTSIDE_ALLOWED_UNIVERSE_SHADOW_ONLY; never write outside-universe symbols into final_enter_candidates, scores, or thesis");
            governance.put("safety_boundary", "governance/shadow-only: do not expand allowed_symbols, do not promote peer_shadow_candidates to tradable, do not override risk gates");
            ArrayNode mandatoryQuestions = governance.putArray("mandatory_questions");
            mandatoryQuestions.add("Identify retained leaders and leadership-only symbols; do not ignore leaders solely because tradable=false or price is high.");
            mandatoryQuestions.add("Check hot leaders, market leadership, divergence between hot_stocks and strong_themes, emerging themes, theme rotation, and fading themes.");
            mandatoryQuestions.add("For OTHER/UNKNOWN/hot leader outside strong themes, propose temporary theme, peer scan result, and confidence.");
            mandatoryQuestions.add("Analyze peer_shadow_candidates by role: SECOND_LEADER, LOW_BASE_FOLLOWER, CHANNEL_DISTRIBUTOR, WATCH_ONLY; keep them out of BUY/ENTER.");

            ObjectNode governanceTrace = root.putObject("theme_governance_trace");
            governanceTrace.put("governanceRequired", governanceRequired);
            governanceTrace.put("requires_leadership_analysis", hasLeadership);
            governanceTrace.put("requires_divergence_analysis", governanceRequired);
            governanceTrace.put("requires_taxonomy_gap_analysis", governanceRequired);
            governanceTrace.put("requires_peer_shadow_analysis", hasPeerShadow);
            governanceTrace.put("must_not_expand_allowed_symbols", true);
            governanceTrace.put("leader_tradable_false_allowed", true);
            governanceTrace.put("peer_shadow_tradable_false_allowed", true);
            governanceTrace.put("violates_allowed_universe_contract", false);

            ArrayNode allowed = root.putArray("allowed_symbols");
            allowedUnion.forEach(allowed::add);
            root.put("leader_tradable_false_allowed", true);
            root.put("must_not_expand_allowed_symbols", true);
            root.put("candidate_scope_contract",
                    "tradable_candidate_symbols/candidates 才可進入 ENTER 評估；leadership_symbols 僅供 MARKET_LEADERSHIP/THEME_VALIDATION/PEER_DISCOVERY，不得視為 ENTER candidate。" );
            root.put("contract_note",
                    "scores.keys 與 thesis.keys 必須是 allowed_symbols 的子集；"
                            + "tradable_candidate_symbols/candidates 是唯一可交易候選；leadership_symbols 可出現在研究脈絡，"
                            + "但 leader_tradable=false 時不得視為 ENTER candidate，也不得放寬 ranking 或 FinalDecisionEngine。前一輪 symbols 僅可作為背景，嚴禁直接複製到本輪 scores/thesis。");

            // 補充 context（由 caller 傳入，如 txf 報價、大盤漲跌家數等）
            if (contextPayload != null && !contextPayload.isBlank()) {
                root.put("market_context", contextPayload);
                try {
                    root.set("market_context_payload", objectMapper.readTree(contextPayload));
                } catch (Exception ignored) {
                    root.put("market_context_parse_warning", "market_context is not valid JSON; consumers should fallback to raw string");
                }
            }

            // 規則檔清單（Claude Code Agent 必讀）
            ArrayNode rules = root.putArray("rules_files");
            RULES_FILES.forEach(rules::add);

            // 輸出路徑（Claude Code Agent 研究完要寫入的位置）
            String outputPath = config.getResearchOutputPath();
            if (outputPath != null && !outputPath.isBlank()) {
                root.put("output_path", outputPath);
            }

            // 建議檔名（包含 taskId，bridge 可直接從檔名解 routing）
            if (taskId != null) {
                String hhmm = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HHmm"));
                root.put("submit_filename_hint",
                        String.format("claude-%s-%s-%s-task-%d.json",
                                type, tradingDate, hhmm, taskId));
            }

            // v2.5：研究輸出契約提醒（路徑 + 驗證腳本）
            root.put("contract_file", CONTRACT_FILE);
            ArrayNode validators = root.putArray("local_validators");
            validators.add(VALIDATOR_PY);
            validators.add(VALIDATOR_MJS);
            root.put("contract_reminder", PROMPT_REMINDER);

            // v2.6：附加即時資金 / 持倉 context，避免 Claude 只讀過舊的 capital-summary.md。
            attachCapitalContext(root);
            attachOpenPositions(root);
            attachPortfolioHealthV2(root);
            root.put("live_context_note",
                    "capital_context/open_positions/position_health_v2 為 Java live context；持倉判讀優先使用 health-v2 的均線/量價/籌碼分級，不得只用 trailing stop 單點價格下 EXIT 結論。");

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Path dest = Paths.get(path);
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            Files.writeString(dest, json, StandardCharsets.UTF_8);
            log.info("[ClaudeCodeRequestWriter] Written taskId={} type={} tradableCandidates={} leadershipSymbols={} to {}",
                    taskId, type,
                    tradableCandidateSymbols == null ? 0 : tradableCandidateSymbols.size(),
                    leadershipContexts == null ? 0 : leadershipContexts.size(),
                    path);
            return true;

        } catch (Exception e) {
            log.warn("[ClaudeCodeRequestWriter] Failed to write request: {}", e.getMessage());
            return false;
        }
    }

    public record LeaderContext(
            String symbol,
            String stockName,
            String themeTag,
            Integer leaderRank,
            boolean leaderTradable,
            String retentionReason,
            List<String> useFor
    ) {}

    public record PeerShadowContext(
            String symbol,
            String role,
            String leaderSymbol,
            String themeTag,
            boolean tradable,
            BigDecimal shadowRankScore,
            String evidenceSummary
    ) {}

    /**
     * 寫入即時資金 context。若無法取得（service 未注入或拋例外），改寫 warning，
     * 不可讓整個 request 寫出失敗。
     */
    private void attachCapitalContext(ObjectNode root) {
        ObjectNode ctx = root.putObject("capital_context");
        if (capitalService == null) {
            ctx.put("warning", "CAPITAL_SERVICE_NOT_AVAILABLE：請依 capital-summary.md / capital_config 估算");
            return;
        }
        try {
            CapitalSummaryResponse s = capitalService.getSummary();
            putBig(ctx, "availableCash",      s.availableCash());
            putBig(ctx, "cashBalance",        s.cashBalance());
            putBig(ctx, "reservedCash",       s.reservedCash());
            putBig(ctx, "investedCost",       s.investedCost());
            putBig(ctx, "investedValue",      s.investedValue());
            putBig(ctx, "unrealizedPnl",      s.unrealizedPnl());
            putBig(ctx, "realizedPnl",        s.realizedPnl());
            putBig(ctx, "totalEquity",        s.totalEquity());
            putBig(ctx, "totalAssets",        s.totalAssets());
            putBig(ctx, "cashRatio",          s.cashRatio());
            ctx.put("openPositionCount",      s.openPositionCount());
            ctx.put("liveQuoteAvailable",     s.liveQuoteAvailable());
            if (s.configNotes() != null)     ctx.put("configNotes",     s.configNotes());
            if (s.configUpdatedAt() != null) ctx.put("configUpdatedAt", s.configUpdatedAt());

            try {
                CapitalConfigEntity cfg = capitalService.getConfig();
                if (cfg != null && cfg.getUpdatedAt() != null) {
                    ctx.put("configUpdatedAtIso",
                            cfg.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                }
            } catch (Exception ignored) { /* 設定讀取失敗不擋主流程 */ }

            ctx.put("source", "trading-system Java live");
        } catch (Exception e) {
            ctx.removeAll();
            ctx.put("warning", "CAPITAL_CONTEXT_FAILED: " + e.getMessage());
        }
    }

    /** 寫入未平倉持倉摘要。任何錯誤都退回成 warning，不影響 request 寫出。 */
    private void attachOpenPositions(ObjectNode root) {
        ArrayNode arr = root.putArray("open_positions");
        if (positionService == null) {
            ObjectNode warn = arr.addObject();
            warn.put("warning", "POSITION_SERVICE_NOT_AVAILABLE");
            return;
        }
        try {
            List<PositionResponse> list = positionService.getOpenPositions(50);
            if (list == null || list.isEmpty()) {
                root.put("open_positions_note", "目前無未平倉持倉");
                return;
            }
            for (PositionResponse p : list) {
                ObjectNode item = arr.addObject();
                if (p.symbol()    != null) item.put("symbol",    p.symbol());
                if (p.stockName() != null) item.put("stockName", p.stockName());
                if (p.side()      != null) item.put("side",      p.side());
                putBig(item, "qty",           p.qty());
                putBig(item, "avgCost",       p.avgCost());
                putBig(item, "stopLossPrice", p.stopLossPrice());
                putBig(item, "takeProfit1",   p.takeProfit1());
                putBig(item, "takeProfit2",   p.takeProfit2());
                if (p.strategyType() != null) item.put("strategyType", p.strategyType());
                if (p.reviewStatus() != null) item.put("reviewStatus", p.reviewStatus());
                if (p.reviewedAt()   != null) item.put("reviewedAt",
                        p.reviewedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
                if (p.reviewReason() != null) item.put("reviewReason", p.reviewReason());
            }
        } catch (Exception e) {
            arr.removeAll();
            ObjectNode warn = arr.addObject();
            warn.put("warning", "OPEN_POSITIONS_FAILED: " + e.getMessage());
        }
    }

    /** 寫入 health-v2 持股結構判讀，讓 Claude/Codex 不再只依 stop/trailing stop 單點價格判斷 EXIT。 */
    private void attachPortfolioHealthV2(ObjectNode root) {
        if (portfolioHealthV2Service == null) {
            ObjectNode warn = root.putObject("position_health_v2");
            warn.put("warning", "PORTFOLIO_HEALTH_V2_SERVICE_NOT_AVAILABLE");
            warn.put("mode", "SHADOW_MANUAL_CONFIRM_ONLY");
            warn.put("autoBuyEnabled", false);
            warn.put("autoSellEnabled", false);
            return;
        }
        try {
            Map<String, Object> health = portfolioHealthV2Service.healthV2ReadOnlySummary();
            root.set("position_health_v2", objectMapper.valueToTree(health));
            root.put("position_health_v2_contract_note",
                    "health-v2 actionTier 僅為 HOLD/SOFT_WARNING/REDUCE_REVIEW/EXIT_REVIEW/HARD_EXIT_ALERT 人工確認分級；autoSellEnabled=false 時不得當成自動賣出。若 open_positions stop 與 health-v2 衝突，以 health-v2 結構分級作為持股分析主依據。");
        } catch (Exception e) {
            ObjectNode warn = root.putObject("position_health_v2");
            warn.put("warning", "POSITION_HEALTH_V2_FAILED: " + e.getMessage());
            warn.put("mode", "SHADOW_MANUAL_CONFIRM_ONLY");
            warn.put("autoBuyEnabled", false);
            warn.put("autoSellEnabled", false);
        }
    }

    private static void putBig(ObjectNode node, String key, BigDecimal value) {
        if (value == null) return;
        node.put(key, value);
    }
}
