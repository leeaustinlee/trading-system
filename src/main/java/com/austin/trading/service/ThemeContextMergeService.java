package com.austin.trading.service;

import com.austin.trading.domain.enums.ThemeRole;
import com.austin.trading.dto.internal.ClaudeThemeResearchOutputDto;
import com.austin.trading.dto.internal.ClaudeThemeResearchOutputDto.SymbolResearch;
import com.austin.trading.dto.internal.ThemeContextDto;
import com.austin.trading.dto.internal.ThemeSnapshotV2Dto;
import com.austin.trading.dto.internal.ThemeSnapshotV2Dto.Theme;
import com.austin.trading.dto.internal.ThemeSnapshotV2Dto.ThemeCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * v2 Theme Engine PR3：Codex snapshot（權威）+ Claude research（語意補充）合併。
 *
 * <h3>合併規則</h3>
 * <ol>
 *   <li>對 snapshot 中每個 theme × 每個 candidate 建立一筆 {@link ThemeContextDto}</li>
 *   <li>查詢 Claude SymbolResearch：key = {@code (symbol, theme_tag)}（大小寫不敏感）</li>
 *   <li>若找到 Claude entry：
 *       <ul>
 *           <li>填 Claude 側 6 欄位（themeRole / themeFitScore / themeDoubt / themeRotationRisk /
 *               stockSpecificCatalyst / riskNotes）</li>
 *           <li>若 Claude entry 有 {@code theme_strength} 非 null → 忽略，warnings 加
 *               {@code IGNORED_CLAUDE_THEME_STRENGTH_OVERRIDE:symbol|theme_tag}</li>
 *       </ul>
 *   </li>
 *   <li>若 Claude 多出來 entry（snapshot 沒這 symbol+theme_tag） → 丟到 {@code rejectedClaudeEntries}</li>
 *   <li>若 Claude entry {@code symbol / theme_tag} 有缺 → 同樣丟 {@code rejectedClaudeEntries}</li>
 * </ol>
 *
 * <p>PR3 本身不會被任何 live decision 消費；service 保持 pure，回傳明確的結果結構供 PR4 gate 使用。</p>
 */
@Service
public class ThemeContextMergeService {

    private static final Logger log = LoggerFactory.getLogger(ThemeContextMergeService.class);

    public static final String WARN_IGNORED_CLAUDE_THEME_STRENGTH = "IGNORED_CLAUDE_THEME_STRENGTH_OVERRIDE";
    public static final String REJECT_MISSING_ALIGNMENT_KEYS      = "REJECT_MISSING_SYMBOL_OR_THEME_TAG";
    public static final String REJECT_NO_SNAPSHOT_MATCH           = "REJECT_NO_SNAPSHOT_MATCH";

    /**
     * 主入口。snapshot 為 null → 回空結果（不 throw，讓 caller 走 WAIT 路徑）。
     *
     * @param snapshot    Codex 題材快照（可為 null；若 null 表示 snapshot 不可用，merge 不執行）
     * @param claudeData  Claude 研究（可為 null；若 null 所有 context 的 Claude 側欄位都是 null）
     */
    public MergeResult merge(ThemeSnapshotV2Dto snapshot, ClaudeThemeResearchOutputDto claudeData) {
        if (snapshot == null || snapshot.themes() == null) {
            return MergeResult.empty("snapshot unavailable");
        }

        // 以 (symbol, themeTag) 為 key 建 Claude index，方便 O(1) 查詢
        Map<String, SymbolResearch> claudeIndex = new LinkedHashMap<>();
        List<String> rejectedClaudeEntries = new ArrayList<>();

        if (claudeData != null && claudeData.symbols() != null) {
            for (SymbolResearch entry : claudeData.symbols()) {
                if (entry == null) continue;
                if (!entry.hasAlignmentKeys()) {
                    rejectedClaudeEntries.add(REJECT_MISSING_ALIGNMENT_KEYS
                            + ":symbol=" + entry.symbol() + ",theme_tag=" + entry.themeTag());
                    continue;
                }
                claudeIndex.put(alignmentKey(entry.symbol(), entry.themeTag()), entry);
            }
        }

        // 逐 snapshot 建 context；同時把已 consumed 的 claude key 移出，最後還留在 index 的都是 no-match
        List<ThemeContextDto> contexts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (Theme theme : snapshot.themes()) {
            if (theme == null || theme.themeTag() == null) continue;
            List<ThemeCandidate> candidates = theme.candidates();
            if (candidates == null || candidates.isEmpty()) continue;

            for (ThemeCandidate cand : candidates) {
                if (cand == null || cand.symbol() == null) continue;
                String key = alignmentKey(cand.symbol(), theme.themeTag());
                SymbolResearch claudeEntry = claudeIndex.remove(key);

                ThemeContextDto ctx = buildContext(theme, cand, claudeEntry, warnings);
                contexts.add(ctx);
            }
        }

        // Remainder in claudeIndex = Claude 提到但 snapshot 沒這 (symbol, theme) 的 entry → reject
        for (Map.Entry<String, SymbolResearch> leftover : claudeIndex.entrySet()) {
            rejectedClaudeEntries.add(REJECT_NO_SNAPSHOT_MATCH + ":" + leftover.getKey());
        }

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("contextCount", contexts.size());
        trace.put("warningCount", warnings.size());
        trace.put("rejectedClaudeCount", rejectedClaudeEntries.size());
        trace.put("claudeProvided", claudeData != null);

        if (!warnings.isEmpty()) {
            log.info("[ThemeContextMerge] warnings={}", warnings);
        }
        if (!rejectedClaudeEntries.isEmpty()) {
            log.info("[ThemeContextMerge] rejected={}", rejectedClaudeEntries);
        }

        return new MergeResult(
                Collections.unmodifiableList(contexts),
                Collections.unmodifiableList(warnings),
                Collections.unmodifiableList(rejectedClaudeEntries),
                Collections.unmodifiableMap(trace)
        );
    }

    // ══════════════════════════════════════════════════════════════════════

    private ThemeContextDto buildContext(Theme theme, ThemeCandidate cand,
                                          SymbolResearch claudeEntry, List<String> warnings) {
        // Codex 權威側
        ThemeRole roleFromSnapshot = cand.roleHintEnum();   // LEADER/FOLLOWER/LAGGARD/UNKNOWN
        ThemeRole roleFinal = roleFromSnapshot;

        // Claude 側（若有）
        java.math.BigDecimal themeFitScore = null;
        java.math.BigDecimal themeDoubt = null;
        java.math.BigDecimal themeRotationRisk = null;
        String stockSpecificCatalyst = null;
        List<String> riskNotes = null;

        if (claudeEntry != null) {
            // Claude 可能改 role（若 snapshot 的 role_hint 是 UNKNOWN，接受 Claude；
            // 若 snapshot 已明確，Claude 不得改）
            ThemeRole claudeRole = claudeEntry.themeRoleEnum();
            if (roleFromSnapshot == ThemeRole.UNKNOWN && claudeRole != ThemeRole.UNKNOWN) {
                roleFinal = claudeRole;
            }

            themeFitScore = claudeEntry.themeFitScore();
            themeDoubt = claudeEntry.themeDoubt();
            themeRotationRisk = claudeEntry.themeRotationRisk();
            stockSpecificCatalyst = claudeEntry.stockSpecificCatalyst();
            riskNotes = claudeEntry.riskNotes() == null ? null
                    : List.copyOf(claudeEntry.riskNotes());

            // ⚠️ 核心規則：Claude 不得覆寫 theme_strength
            if (claudeEntry.themeStrength() != null) {
                String warn = WARN_IGNORED_CLAUDE_THEME_STRENGTH + ":"
                        + alignmentKey(claudeEntry.symbol(), claudeEntry.themeTag())
                        + ":claudeValue=" + claudeEntry.themeStrength();
                warnings.add(warn);
            }
        }

        return new ThemeContextDto(
                cand.symbol(),
                theme.themeTag(),
                theme.themeStrength(),         // ← 永遠來自 snapshot，絕不使用 claude 版本
                theme.trendStageEnum(),
                theme.rotationSignalEnum(),
                theme.sustainabilityScore(),
                theme.freshnessScore(),
                theme.crowdingRiskEnum(),
                theme.confidence(),
                theme.evidenceSources() == null ? null : List.copyOf(theme.evidenceSources()),
                roleFinal,
                themeFitScore,
                themeDoubt,
                themeRotationRisk,
                stockSpecificCatalyst,
                riskNotes
        );
    }

    private String alignmentKey(String symbol, String themeTag) {
        String s = symbol == null ? "" : symbol.trim().toUpperCase(Locale.ROOT);
        String t = themeTag == null ? "" : themeTag.trim().toUpperCase(Locale.ROOT);
        return s + "|" + t;
    }

    // ══════════════════════════════════════════════════════════════════════

    /**
     * 合併結果。
     *
     * @param contexts               每檔 (symbol, theme_tag) 一筆
     * @param warnings               non-blocking warning（e.g. IGNORED_CLAUDE_THEME_STRENGTH_OVERRIDE:2454|AI_SERVER:claudeValue=8.0）
     * @param rejectedClaudeEntries  Claude 側被丟棄的 entry 原因
     * @param trace                  統計摘要（可直接塞進 decision trace）
     */
    public record MergeResult(
            List<ThemeContextDto> contexts,
            List<String> warnings,
            List<String> rejectedClaudeEntries,
            Map<String, Object> trace
    ) {
        public Optional<ThemeContextDto> findBySymbolAndTheme(String symbol, String themeTag) {
            if (symbol == null || themeTag == null) return Optional.empty();
            return contexts.stream()
                    .filter(c -> symbol.equals(c.symbol()) && themeTag.equalsIgnoreCase(c.themeTag()))
                    .findFirst();
        }

        public List<ThemeContextDto> findBySymbol(String symbol) {
            if (symbol == null) return List.of();
            return contexts.stream().filter(c -> symbol.equals(c.symbol())).toList();
        }

        public static MergeResult empty(String reason) {
            Map<String, Object> trace = new LinkedHashMap<>();
            trace.put("contextCount", 0);
            trace.put("reason", reason);
            return new MergeResult(List.of(), List.of(), List.of(),
                    Collections.unmodifiableMap(trace));
        }
    }
}
