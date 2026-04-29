package com.austin.trading.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0.4 (2026-04-29) session-aware {@code require_codex} 純邏輯測試。
 *
 * <p>覆蓋 {@link FinalDecisionService#shouldRequireCodexForSession(String, boolean, boolean)}：</p>
 * <ul>
 *   <li>session_aware=true：PREMARKET / POSTMARKET / T86_TOMORROW 自動視為 false（觀察 session 不阻塞），
 *       OPENING / MIDDAY 仍採用 globalRequireCodex。</li>
 *   <li>session_aware=false：退回 legacy 行為，所有 session 都採用 globalRequireCodex。</li>
 *   <li>未知 / null sourceTaskType：保守 fallback，採用 globalRequireCodex。</li>
 * </ul>
 *
 * <p>背景：30 天 final_decision 0 ENTER。原因之一是 {@code require_codex=true} 在
 * PREMARKET / POSTMARKET / T86_TOMORROW 也強制等 Codex，但這 3 個 session 設計上 Codex
 * 一律回 {@code WATCH_ONLY (codexBucket)} + reason {@code PREMARKET_BIAS_ONLY}，
 * 等於結構性鎖死 60% 決策時點。本邏輯把這 3 個 session 的 require_codex 解析為 false，
 * OPENING / MIDDAY 行為不變。</p>
 */
class FinalDecisionServiceSessionAwareTests {

    // ── session_aware = true（預設啟用） ─────────────────────────────────────

    @Test
    void premarket_sessionAware_doesNotRequireCodex_evenIfGlobalTrue() {
        // PREMARKET + globalRequireCodex=true + sessionAware=true
        // → 預期 effective = false（不因 require_codex=true 就降級為 REST）
        boolean effective = FinalDecisionService.shouldRequireCodexForSession(
                "PREMARKET", true, true);
        assertThat(effective).isFalse();
    }

    @Test
    void postmarket_sessionAware_doesNotRequireCodex_evenIfGlobalTrue() {
        boolean effective = FinalDecisionService.shouldRequireCodexForSession(
                "POSTMARKET", true, true);
        assertThat(effective).isFalse();
    }

    @Test
    void t86Tomorrow_sessionAware_doesNotRequireCodex_evenIfGlobalTrue() {
        boolean effective = FinalDecisionService.shouldRequireCodexForSession(
                "T86_TOMORROW", true, true);
        assertThat(effective).isFalse();
    }

    @Test
    void opening_sessionAware_stillRequiresCodex_whenGlobalTrue() {
        // OPENING + Codex 沒回應 → 仍然降級（行為不變）
        boolean effective = FinalDecisionService.shouldRequireCodexForSession(
                "OPENING", true, true);
        assertThat(effective).isTrue();
    }

    @Test
    void midday_sessionAware_stillRequiresCodex_whenGlobalTrue() {
        boolean effective = FinalDecisionService.shouldRequireCodexForSession(
                "MIDDAY", true, true);
        assertThat(effective).isTrue();
    }

    @Test
    void opening_sessionAware_returnsFalse_whenGlobalFalse() {
        // 全域 flag 已關閉時，OPENING 自然也不需要 Codex
        boolean effective = FinalDecisionService.shouldRequireCodexForSession(
                "OPENING", false, true);
        assertThat(effective).isFalse();
    }

    @Test
    void lowerCase_sessionAware_isCaseInsensitive() {
        // sourceTaskType 大小寫不敏感
        assertThat(FinalDecisionService.shouldRequireCodexForSession("premarket", true, true)).isFalse();
        assertThat(FinalDecisionService.shouldRequireCodexForSession("opening",   true, true)).isTrue();
    }

    // ── session_aware = false（legacy 行為） ────────────────────────────────

    @Test
    void sessionAwareDisabled_premarket_fallsBackToLegacyRequireCodexTrue() {
        // session_aware flag = false → 退回 legacy 全 require 行為
        boolean effective = FinalDecisionService.shouldRequireCodexForSession(
                "PREMARKET", true, false);
        assertThat(effective).isTrue();
    }

    @Test
    void sessionAwareDisabled_postmarket_fallsBackToLegacy() {
        boolean effective = FinalDecisionService.shouldRequireCodexForSession(
                "POSTMARKET", true, false);
        assertThat(effective).isTrue();
    }

    @Test
    void sessionAwareDisabled_opening_fallsBackToLegacy() {
        boolean effective = FinalDecisionService.shouldRequireCodexForSession(
                "OPENING", true, false);
        assertThat(effective).isTrue();
    }

    @Test
    void sessionAwareDisabled_globalFalse_stillFalse() {
        // 即使 legacy 行為，全域 flag = false 時也不 require
        boolean effective = FinalDecisionService.shouldRequireCodexForSession(
                "PREMARKET", false, false);
        assertThat(effective).isFalse();
    }

    // ── 邊界：null / 未知 sourceTaskType（保守 fallback） ──────────────────

    @Test
    void nullSourceTaskType_sessionAware_fallsBackToGlobal() {
        // 沒拿到 task type 資訊 → 保守採用 globalRequireCodex
        assertThat(FinalDecisionService.shouldRequireCodexForSession(null, true,  true)).isTrue();
        assertThat(FinalDecisionService.shouldRequireCodexForSession(null, false, true)).isFalse();
    }

    @Test
    void blankSourceTaskType_sessionAware_fallsBackToGlobal() {
        assertThat(FinalDecisionService.shouldRequireCodexForSession("",    true, true)).isTrue();
        assertThat(FinalDecisionService.shouldRequireCodexForSession("   ", true, true)).isTrue();
    }

    @Test
    void unknownSourceTaskType_sessionAware_fallsBackToGlobal() {
        // 未來新增的 session 在白名單前，先採保守路徑
        assertThat(FinalDecisionService.shouldRequireCodexForSession("UNKNOWN_SESSION", true, true)).isTrue();
        assertThat(FinalDecisionService.shouldRequireCodexForSession("WEEKLY_REVIEW",   true, true)).isTrue();
    }
}
