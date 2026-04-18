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
        DEFAULTS.put("candidate.scan.maxCount",         new String[]{"10",    "INTEGER", "全市場掃描後候選股最大數量"});
        DEFAULTS.put("candidate.research.maxCount",     new String[]{"5",     "INTEGER", "送交 Claude 深度研究的候選股數量"});
        DEFAULTS.put("decision.final.maxCount",         new String[]{"2",     "INTEGER", "最終名單最大數量"});
        DEFAULTS.put("scoring.java_weight",             new String[]{"0.50",  "DECIMAL", "Java 結構評分權重"});
        DEFAULTS.put("scoring.claude_weight",           new String[]{"0.35",  "DECIMAL", "Claude 研究評分權重"});
        DEFAULTS.put("scoring.codex_weight",            new String[]{"0.15",  "DECIMAL", "Codex 審核評分權重"});
        DEFAULTS.put("scoring.rr_min_grade_a",          new String[]{"1.8",   "DECIMAL", "A 級市場最低風報比"});
        DEFAULTS.put("scoring.rr_min_grade_b",          new String[]{"2.0",   "DECIMAL", "B 級市場最低風報比"});
        DEFAULTS.put("scoring.enable_codex_review",     new String[]{"false", "BOOLEAN", "是否啟用 Codex review 層"});
        DEFAULTS.put("scoring.late_stop_market_grade",  new String[]{"A",     "STRING",  "10:30 後允許進場的最低市場等級"});
        DEFAULTS.put("scoring.theme_weight_in_java",    new String[]{"0.30",  "DECIMAL", "題材分在 Java 結構評分中的佔比"});
        DEFAULTS.put("scheduling.line_notify_enabled",  new String[]{"false", "BOOLEAN", "是否啟用 Java 直接發 LINE"});
        DEFAULTS.put("scoring.cooldown_minutes",        new String[]{"30",    "INTEGER", "每筆交易後的冷卻期（分鐘）"});
        DEFAULTS.put("scoring.version",                 new String[]{"v1.0",  "STRING",  "目前評分規則版本號"});
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
