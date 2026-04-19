package com.austin.trading.service;

import com.austin.trading.dto.response.ScoreConfigResponse;
import com.austin.trading.entity.ScoreConfigEntity;
import com.austin.trading.repository.ScoreConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 評分參數設定服務。
 * <p>
 * 啟動時從 DB 載入所有 score_config 進本地快取，
 * 所有 Engine 透過此服務讀取參數，不再 hard-code 常數。
 * 更新時同步更新快取，避免重啟才生效。
 * </p>
 */
@Service
public class ScoreConfigService {

    private static final Logger log = LoggerFactory.getLogger(ScoreConfigService.class);

    private final ScoreConfigRepository repository;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    // ── 預設值（DB 中若無對應 key 時使用）─────────────────────────────────────
    private static final Map<String, String[]> DEFAULTS = new LinkedHashMap<>();

    static {
        // format: key -> {value, valueType, description}
        // ── 候選股數量 ────────────────────────────────────────────────────────
        DEFAULTS.put("candidate.scan.maxCount",         new String[]{"8",     "INTEGER", "全市場掃描後候選股最大數量（v2.0：8）"});
        DEFAULTS.put("candidate.research.maxCount",     new String[]{"3",     "INTEGER", "送交 Claude 深度研究的候選股數量（v2.0：3）"});
        DEFAULTS.put("candidate.notify.maxCount",       new String[]{"5",     "INTEGER", "盤後 LINE 通知最大候選股數"});
        DEFAULTS.put("decision.final.maxCount",         new String[]{"2",     "INTEGER", "最終名單最大數量"});
        DEFAULTS.put("decision.require_entry_trigger",  new String[]{"true",  "BOOLEAN", "進場需有明確觸發訊號（突破/回測確認）"});
        // ── 評分權重 ──────────────────────────────────────────────────────────
        DEFAULTS.put("scoring.java_weight",             new String[]{"0.40",  "DECIMAL", "Java 結構評分權重（v2.0）"});
        DEFAULTS.put("scoring.claude_weight",           new String[]{"0.35",  "DECIMAL", "Claude 研究評分權重（v2.0）"});
        DEFAULTS.put("scoring.codex_weight",            new String[]{"0.25",  "DECIMAL", "Codex 審核評分權重（v2.0）"});
        DEFAULTS.put("scoring.rr_min_grade_a",          new String[]{"2.2",   "DECIMAL", "A 級市場最低風報比（v2.0）"});
        DEFAULTS.put("scoring.rr_min_grade_b",          new String[]{"2.5",   "DECIMAL", "B 級市場最低風報比（v2.0）"});
        DEFAULTS.put("scoring.rr_min_ap",               new String[]{"2.5",   "DECIMAL", "A+ 等級最低風報比"});
        DEFAULTS.put("scoring.enable_codex_review",     new String[]{"true",  "BOOLEAN", "是否啟用 Codex review 層（v2.0 預設 true）"});
        DEFAULTS.put("scoring.late_stop_market_grade",  new String[]{"A",     "STRING",  "10:30 後允許進場的最低市場等級"});
        DEFAULTS.put("scoring.java_rr_multiplier",       new String[]{"1.3",   "DECIMAL", "RR 換算為 Java 結構分的乘數（v2.0：1.3）"});
        DEFAULTS.put("scoring.theme_weight_in_java",    new String[]{"0.20",  "DECIMAL", "題材分在 Java 結構評分中的佔比（v2.0：0.20）"});
        DEFAULTS.put("scheduling.line_notify_enabled",  new String[]{"false", "BOOLEAN", "是否啟用 Java 直接發 LINE"});
        DEFAULTS.put("scoring.cooldown_minutes",        new String[]{"45",    "INTEGER", "每筆交易後的冷卻期（v2.0：45 分鐘）"});
        DEFAULTS.put("scoring.version",                 new String[]{"v2.0-bc-sniper", "STRING", "目前評分規則版本號"});
        // ── 分級門檻（A+/A/B/C）────────────────────────────────────────────────
        DEFAULTS.put("scoring.grade_ap_min",            new String[]{"8.8",   "DECIMAL", "A+ 等級 final_rank_score 門檻（可交易的唯一等級）"});
        DEFAULTS.put("scoring.grade_a_min",             new String[]{"8.2",   "DECIMAL", "A 等級 final_rank_score 門檻"});
        DEFAULTS.put("scoring.grade_b_min",             new String[]{"7.4",   "DECIMAL", "B 等級 final_rank_score 門檻"});
        // ── Veto 門檻 ─────────────────────────────────────────────────────────
        DEFAULTS.put("veto.theme_rank_max",             new String[]{"2",     "INTEGER", "題材排名超過此值則 veto（v2.0：2）"});
        DEFAULTS.put("veto.final_theme_score_min",      new String[]{"7.5",   "DECIMAL", "題材分低於此值則 veto（v2.0）"});
        DEFAULTS.put("veto.codex_score_min",            new String[]{"6.5",   "DECIMAL", "Codex 分低於此值則 veto（v2.0）"});
        DEFAULTS.put("veto.score_divergence_max",       new String[]{"2.5",   "DECIMAL", "Java/Claude 分差超過此值則 veto（v2.0）"});
        DEFAULTS.put("veto.require_theme",              new String[]{"true",  "BOOLEAN", "無題材標籤時是否自動 veto（v2.0 預設 true）"});
        // ── 共識評分懲罰係數（v2.0 BC Sniper）────────────────────────────────
        DEFAULTS.put("consensus.penalty_jc",            new String[]{"0.25",  "DECIMAL", "Java vs Claude 分歧懲罰係數（v2.0）"});
        DEFAULTS.put("consensus.penalty_jx",            new String[]{"0.20",  "DECIMAL", "Java vs Codex 分歧懲罰係數（v2.0）"});
        DEFAULTS.put("consensus.penalty_cx",            new String[]{"0.20",  "DECIMAL", "Claude vs Codex 分歧懲罰係數（v2.0）"});
        // ── 題材相關 ──────────────────────────────────────────────────────────
        DEFAULTS.put("theme.weight.market_behavior",    new String[]{"0.55",  "DECIMAL", "題材行為分（Java量化）佔 final_theme_score 權重（v2.0）"});
        DEFAULTS.put("theme.weight.heat",               new String[]{"0.25",  "DECIMAL", "題材熱度分（Claude語意）佔 final_theme_score 權重（v2.0）"});
        DEFAULTS.put("theme.weight.continuation",       new String[]{"0.20",  "DECIMAL", "題材延續分（Claude語意）佔 final_theme_score 權重"});
        DEFAULTS.put("theme.strong_stock_threshold_pct",new String[]{"2.5",   "DECIMAL", "個股漲幅 >= 此值視為強勢股（v2.0：2.5%）"});

        // ── Position Review — 持倉決策 ────────────────────────────────────────
        DEFAULTS.put("position.review.theme_rank_max_for_strong",   new String[]{"3",     "INTEGER", "STRONG 時題材排名需 ≤ 此值"});
        DEFAULTS.put("position.review.theme_score_min_for_strong",  new String[]{"7.0",   "DECIMAL", "STRONG 時題材分需 ≥ 此值"});
        DEFAULTS.put("position.review.max_holding_days",            new String[]{"15",    "INTEGER", "絕對持有天數上限"});
        DEFAULTS.put("position.review.weaken_theme_score",          new String[]{"6.0",   "DECIMAL", "題材分 < 此 → WEAKEN"});
        DEFAULTS.put("position.review.exit_loss_pct",               new String[]{"6.0",   "DECIMAL", "虧損 % ≥ 此 → EXIT"});
        DEFAULTS.put("position.review.stale_days_without_momentum", new String[]{"7",     "INTEGER", "持有 ≥ N 天且無動能 → 傾向 EXIT"});
        DEFAULTS.put("position.review.exit_if_extended_and_weak",   new String[]{"true",  "BOOLEAN", "EXTREME 延伸 + 動能弱 → EXIT"});
        DEFAULTS.put("position.review.extended_weaken_override",    new String[]{"true",  "BOOLEAN", "MILD 延伸時即使獲利也不給 STRONG"});
        DEFAULTS.put("position.review.drawdown_from_high_weaken_pct",new String[]{"3.0",  "DECIMAL", "從高點回撤 ≥ 此 % 且無動能 → WEAKEN"});
        DEFAULTS.put("position.review.drawdown_from_high_exit_pct",  new String[]{"5.0",  "DECIMAL", "從高點回撤 ≥ 此 % → EXIT"});

        // ── Trailing Stop — 移動停利 ──────────────────────────────────────────
        DEFAULTS.put("position.trailing.breakeven_pct",             new String[]{"3.0",   "DECIMAL", "獲利 ≥ 此 % → 停損移到成本"});
        DEFAULTS.put("position.trailing.first_trail_pct",           new String[]{"5.0",   "DECIMAL", "獲利 ≥ 此 % → 停損移到 entry+buffer"});
        DEFAULTS.put("position.trailing.second_trail_pct",          new String[]{"8.0",   "DECIMAL", "獲利 ≥ 此 % → 停損移到 dayLow 附近"});
        DEFAULTS.put("position.trailing.buffer_pct",                new String[]{"1.5",   "DECIMAL", "上移停利時的緩衝 %"});

        // ── Watchlist — 觀察名單 ──────────────────────────────────────────────
        DEFAULTS.put("watchlist.min_score_to_track",                new String[]{"6.0",   "DECIMAL", "加入觀察最低分"});
        DEFAULTS.put("watchlist.min_score_to_ready",                new String[]{"7.0",   "DECIMAL", "升級 READY 最低分"});
        DEFAULTS.put("watchlist.min_consecutive_strong_days",       new String[]{"2",     "INTEGER", "READY 需連續強勢天數"});
        DEFAULTS.put("watchlist.max_observation_days",              new String[]{"10",    "INTEGER", "超過此天數 → EXPIRE"});
        DEFAULTS.put("watchlist.drop_score_threshold",              new String[]{"5.0",   "DECIMAL", "分數 < 此 → DROP"});
        DEFAULTS.put("watchlist.theme_rank_max_for_tracking",       new String[]{"5",     "INTEGER", "TRACKING 題材排名放寬"});
        DEFAULTS.put("watchlist.theme_rank_max_for_ready",          new String[]{"2",     "INTEGER", "READY 題材排名更嚴"});
        DEFAULTS.put("watchlist.theme_score_min_for_tracking",      new String[]{"6.0",   "DECIMAL", "TRACKING 題材分寬鬆"});
        DEFAULTS.put("watchlist.theme_score_min_for_ready",         new String[]{"7.0",   "DECIMAL", "READY 題材分更嚴"});
        DEFAULTS.put("watchlist.market_grade_c_block_add",           new String[]{"true",  "BOOLEAN", "市場 C 級時不新增觀察股"});
        DEFAULTS.put("watchlist.market_grade_b_ready_score_bonus",  new String[]{"0.5",   "DECIMAL", "市場 B 級時 READY 門檻加分"});
        DEFAULTS.put("watchlist.decay_enabled",                     new String[]{"true",  "BOOLEAN", "是否啟用觀察時間衰退"});
        DEFAULTS.put("watchlist.decay_per_day",                     new String[]{"0.15",  "DECIMAL", "每多��察一天扣的分數"});
        DEFAULTS.put("watchlist.decay_grace_days",                  new String[]{"3",     "INTEGER", "前 N 天不扣分"});

        // ── Cooldown — 交易冷卻 ───────────────────────────────────────────────
        DEFAULTS.put("trading.cooldown.enabled",                    new String[]{"true",  "BOOLEAN", "是否啟用交易冷卻"});
        DEFAULTS.put("trading.cooldown.after_exit_minutes",         new String[]{"60",    "INTEGER", "出場後冷卻分鐘"});
        DEFAULTS.put("trading.cooldown.after_loss_exit_minutes",    new String[]{"1440",  "INTEGER", "虧損出場後冷卻分鐘（預設 1 天）"});
        DEFAULTS.put("trading.cooldown.same_symbol_only",           new String[]{"true",  "BOOLEAN", "冷卻只限同 symbol"});
        DEFAULTS.put("trading.cooldown.same_theme_enabled",         new String[]{"true",  "BOOLEAN", "同題材出場後也冷卻"});
        DEFAULTS.put("trading.cooldown.same_theme_minutes",         new String[]{"720",   "INTEGER", "同題材冷卻分鐘（預設半天）"});
        DEFAULTS.put("trading.cooldown.consecutive_loss_max",      new String[]{"3",     "INTEGER", "連續虧損 N 筆後禁止新倉"});
        DEFAULTS.put("trading.cooldown.daily_loss_limit",          new String[]{"5000",  "DECIMAL", "當日累計虧損超過此金額禁止新倉"});

        // ── Portfolio — 倉位控制 ──────────────────────────────────────────────
        DEFAULTS.put("portfolio.max_open_positions",                new String[]{"3",     "INTEGER", "最多同時持倉數"});
        DEFAULTS.put("portfolio.same_theme_max",                    new String[]{"1",     "INTEGER", "同題材最多持有數"});
        DEFAULTS.put("portfolio.reserve_cash_pct",                  new String[]{"30",    "INTEGER", "保留現金 %"});
        DEFAULTS.put("portfolio.allow_new_when_full_strong",        new String[]{"false", "BOOLEAN", "滿倉全 STRONG 時是否允許新倉"});
        DEFAULTS.put("portfolio.replace_strong_score_gap",          new String[]{"1.5",   "DECIMAL", "新倉需高出 STRONG 持股最低分的差距"});

        // ── Trade Review / Backtest / Recommendation ──────────────────────
        DEFAULTS.put("review.auto_on_close",                       new String[]{"true",  "BOOLEAN", "position close 時自動產生 trade review"});
        DEFAULTS.put("review.chased_high_pct",                     new String[]{"3.0",   "DECIMAL", "進場價距日高 < 此 % 視為追高"});
        DEFAULTS.put("review.profit_giveback_pct",                 new String[]{"50.0",  "DECIMAL", "MFE 回吐超過此 % 標記 GAVE_BACK_PROFIT"});
        DEFAULTS.put("review.held_too_long_days",                  new String[]{"12",    "INTEGER", "持有超過此天數且低報酬 → HELD_TOO_LONG"});
        DEFAULTS.put("recommendation.min_sample_size",             new String[]{"10",    "INTEGER", "分組統計 < 此筆數時 confidence 降為 LOW 或跳過"});
    }

    public ScoreConfigService(ScoreConfigRepository repository) {
        this.repository = repository;
    }

    /** 啟動時種植預設值（idempotent）並載入快取 */
    @PostConstruct
    @Transactional
    public void init() {
        DEFAULTS.forEach((key, arr) -> {
            if (repository.findByConfigKey(key).isEmpty()) {
                ScoreConfigEntity e = new ScoreConfigEntity();
                e.setConfigKey(key);
                e.setConfigValue(arr[0]);
                e.setValueType(arr[1]);
                e.setDescription(arr[2]);
                repository.save(e);
                log.info("[ScoreConfig] seed default: {}={}", key, arr[0]);
            }
        });
        reloadCache();
    }

    private void reloadCache() {
        cache.clear();
        repository.findAll().forEach(e -> cache.put(e.getConfigKey(), e.getConfigValue()));
    }

    // ── 型別安全讀取 ────────────────────────────────────────────────────────

    public String getString(String key, String defaultValue) {
        return cache.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String v = cache.get(key);
        if (v == null) return defaultValue;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        String v = cache.get(key);
        if (v == null) return defaultValue;
        try { return new BigDecimal(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String v = cache.get(key);
        if (v == null) return defaultValue;
        return "true".equalsIgnoreCase(v.trim());
    }

    // ── 更新 ────────────────────────────────────────────────────────────────

    @Transactional
    public ScoreConfigResponse update(String key, String value) {
        ScoreConfigEntity entity = repository.findByConfigKey(key)
                .orElseThrow(() -> new RuntimeException("設定 key 不存在: " + key));
        entity.setConfigValue(value);
        repository.save(entity);
        cache.put(key, value);
        return toResponse(entity);
    }

    // ── 查詢 ────────────────────────────────────────────────────────────────

    public List<ScoreConfigResponse> getAll() {
        return repository.findAllByOrderByConfigKeyAsc().stream().map(this::toResponse).toList();
    }

    public ScoreConfigResponse getByKey(String key) {
        return repository.findByConfigKey(key).map(this::toResponse)
                .orElseThrow(() -> new RuntimeException("設定 key 不存在: " + key));
    }

    private ScoreConfigResponse toResponse(ScoreConfigEntity e) {
        return new ScoreConfigResponse(
                e.getId(), e.getConfigKey(), e.getConfigValue(),
                e.getValueType(), e.getDescription(), e.getUpdatedAt()
        );
    }
}
