package com.austin.trading.config;

import com.austin.trading.service.ScoreConfigService;
import org.springframework.stereotype.Component;

/**
 * v2 Theme Engine PR2：snapshot 讀寫服務的設定存取 facade。
 *
 * <p>底層仍由 {@link ScoreConfigService} 持有（DB-backed，可盤中動態調整）。
 * 這層只做 type-safe 包裝，方便 {@link com.austin.trading.service.ThemeSnapshotService}
 * 與測試使用。</p>
 *
 * <h3>設定 key 對應</h3>
 * <ul>
 *   <li>{@code theme.engine.v2.enabled}（v2.13 起預設 true，shadow 必開）— 主 flag；關閉時 service 直接回 DISABLED</li>
 *   <li>{@code theme.snapshot.validation.enabled}（預設 true）— schema 驗證開關</li>
 *   <li>{@code theme.snapshot.fallback.enabled}（預設 true）— stale / invalid 時是否退回到上一份有效快照</li>
 *   <li>{@code theme.snapshot.path}（預設 {@code D:\ai\stock\theme-snapshot.json}）</li>
 *   <li>{@code theme.snapshot.max_age_minutes}（預設 30）— 超過此分鐘數視為 stale</li>
 * </ul>
 */
@Component
public class ThemeSnapshotProperties {

    public static final String DEFAULT_PATH        = "D:\\ai\\stock\\theme-snapshot.json";
    public static final int    DEFAULT_MAX_AGE_MIN = 30;

    // PR3：Claude theme research settings
    public static final String DEFAULT_CLAUDE_RESEARCH_PATH = "D:\\ai\\stock\\claude-theme-research.json";
    public static final int    DEFAULT_CLAUDE_MAX_AGE_MIN   = 120;

    private final ScoreConfigService config;

    public ThemeSnapshotProperties(ScoreConfigService config) {
        this.config = config;
    }

    public String snapshotPath() {
        return config.getString("theme.snapshot.path", DEFAULT_PATH);
    }

    public int maxAgeMinutes() {
        return config.getInt("theme.snapshot.max_age_minutes", DEFAULT_MAX_AGE_MIN);
    }

    public boolean engineEnabled() {
        return config.getBoolean("theme.engine.v2.enabled", true);
    }

    public boolean validationEnabled() {
        return config.getBoolean("theme.snapshot.validation.enabled", true);
    }

    public boolean fallbackEnabled() {
        return config.getBoolean("theme.snapshot.fallback.enabled", true);
    }

    // ── PR3：Claude theme research ───────────────────────────────────────

    public String claudeResearchPath() {
        return config.getString("theme.claude.research.path", DEFAULT_CLAUDE_RESEARCH_PATH);
    }

    public int claudeResearchMaxAgeMinutes() {
        return config.getInt("theme.claude.research.max_age_minutes", DEFAULT_CLAUDE_MAX_AGE_MIN);
    }

    public boolean claudeContextMergeEnabled() {
        return config.getBoolean("theme.claude.context.merge.enabled", false);
    }

    // ── PR4：Gate trace ──────────────────────────────────────────────────

    public boolean gateTraceEnabled() {
        return config.getBoolean("theme.gate.trace.enabled", true);
    }

    // ── PR5：Shadow mode ────────────────────────────────────────────────

    public static final String DEFAULT_SHADOW_REPORT_PATH = "D:\\ai\\stock\\logs";

    public boolean shadowModeEnabled() {
        return config.getBoolean("theme.shadow_mode.enabled", true);
    }

    /**
     * 取得 shadow report 輸出資料夾；若 {@code theme.shadow_report.path_wsl} 非空則優先使用
     * （用於 WSL 環境跑 Java 時把 {@code D:\...} 對應到 {@code /mnt/d/...}）。
     */
    public String shadowReportPath() {
        String wsl = config.getString("theme.shadow_report.path_wsl", "");
        if (wsl != null && !wsl.isBlank()) return wsl;
        return config.getString("theme.shadow_report.path", DEFAULT_SHADOW_REPORT_PATH);
    }

    public boolean lineSummaryEnabled() {
        return config.getBoolean("theme.line.summary.enabled", false);
    }

    // ── PR6：Live decision override（Phase 3）────────────────────────────

    public boolean liveDecisionEnabled() {
        return config.getBoolean("theme.live_decision.enabled", false);
    }

    /** PR6 保留旗標；PR6 不啟用 WAIT override，設為 true 時 ThemeLiveDecisionService 才會 remove WAIT 項。 */
    public boolean liveDecisionWaitOverrideEnabled() {
        return config.getBoolean("theme.live_decision.wait_override.enabled", false);
    }
}
