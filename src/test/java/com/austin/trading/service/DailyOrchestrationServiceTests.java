package com.austin.trading.service;

import com.austin.trading.entity.DailyOrchestrationStatusEntity;
import com.austin.trading.repository.DailyOrchestrationStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DailyOrchestrationService 單元測試。
 * <p>透過 mock repository 模擬 DB state，驗證 markRunning / markDone / markFailed 的
 * 各種狀態轉換邏輯。</p>
 */
class DailyOrchestrationServiceTests {

    private DailyOrchestrationStatusRepository repository;
    private ScoreConfigService config;
    private DailyOrchestrationService service;

    /** 模擬 DB：trading_date → entity。 */
    private Map<LocalDate, DailyOrchestrationStatusEntity> store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(DailyOrchestrationStatusRepository.class);
        config = mock(ScoreConfigService.class);
        store = new HashMap<>();

        // 使用預設值（stale_minutes=15, enforce_idempotency=true）
        when(config.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(config.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));

        when(repository.findForUpdate(any(LocalDate.class))).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0, LocalDate.class))));
        when(repository.findByTradingDate(any(LocalDate.class))).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0, LocalDate.class))));
        when(repository.save(any(DailyOrchestrationStatusEntity.class))).thenAnswer(inv -> {
            DailyOrchestrationStatusEntity e = inv.getArgument(0);
            // save 後讓 updated_at 有值（模擬 JPA 行為）
            if (e.getUpdatedAt() == null) {
                setUpdatedAt(e, LocalDateTime.now());
            }
            store.put(e.getTradingDate(), e);
            return e;
        });

        service = new DailyOrchestrationService(repository, config);
    }

    // ── 1. markRunning 新建 row → true ────────────────────────────────────
    @Test
    void markRunning_noExistingRow_shouldCreateAndReturnTrue() {
        LocalDate today = LocalDate.of(2026, 4, 20);
        boolean r = service.markRunning(today, OrchestrationStep.PREMARKET_NOTIFY);
        assertThat(r).isTrue();
        DailyOrchestrationStatusEntity e = store.get(today);
        assertThat(e).isNotNull();
        assertThat(e.getStepPremarketNotify()).isEqualTo(DailyOrchestrationService.STATUS_RUNNING);
    }

    // ── 2. markRunning 同一 step 再叫 → false（DONE 擋住）──────────────────
    @Test
    void markRunning_afterDone_sameStep_shouldReturnFalse() {
        LocalDate today = LocalDate.of(2026, 4, 20);
        assertThat(service.markRunning(today, OrchestrationStep.PREMARKET_NOTIFY)).isTrue();
        service.markDone(today, OrchestrationStep.PREMARKET_NOTIFY, "ok");

        // 再叫一次同 step，應該 false
        boolean second = service.markRunning(today, OrchestrationStep.PREMARKET_NOTIFY);
        assertThat(second).isFalse();
        assertThat(store.get(today).getStepPremarketNotify())
                .isEqualTo(DailyOrchestrationService.STATUS_DONE);
    }

    // ── 3. markRunning 卡死 15+ 分鐘的 RUNNING → true（強制覆蓋）───────────
    @Test
    void markRunning_staleRunning_shouldOverrideAndReturnTrue() {
        LocalDate today = LocalDate.of(2026, 4, 20);
        // 預先建立卡死的 entity：RUNNING 且 updated_at 為 20 分鐘前
        DailyOrchestrationStatusEntity stuck = new DailyOrchestrationStatusEntity();
        stuck.setTradingDate(today);
        stuck.setStepPremarketNotify(DailyOrchestrationService.STATUS_RUNNING);
        setUpdatedAt(stuck, LocalDateTime.now().minusMinutes(20));
        store.put(today, stuck);

        boolean r = service.markRunning(today, OrchestrationStep.PREMARKET_NOTIFY);
        assertThat(r).isTrue();
        assertThat(store.get(today).getStepPremarketNotify())
                .isEqualTo(DailyOrchestrationService.STATUS_RUNNING);
    }

    // ── 3b. markRunning 新鮮的 RUNNING → false（避免併發撞上）───────────────
    @Test
    void markRunning_freshRunning_shouldReturnFalse() {
        LocalDate today = LocalDate.of(2026, 4, 20);
        DailyOrchestrationStatusEntity running = new DailyOrchestrationStatusEntity();
        running.setTradingDate(today);
        running.setStepPremarketNotify(DailyOrchestrationService.STATUS_RUNNING);
        setUpdatedAt(running, LocalDateTime.now().minusMinutes(2));
        store.put(today, running);

        boolean r = service.markRunning(today, OrchestrationStep.PREMARKET_NOTIFY);
        assertThat(r).isFalse();
    }

    // ── 4. markRunning 不同 step 同日 → true（各 step 獨立）────────────────
    @Test
    void markRunning_differentStepsSameDay_shouldBothSucceed() {
        LocalDate today = LocalDate.of(2026, 4, 20);

        boolean a = service.markRunning(today, OrchestrationStep.PREMARKET_NOTIFY);
        service.markDone(today, OrchestrationStep.PREMARKET_NOTIFY, "ok");

        boolean b = service.markRunning(today, OrchestrationStep.FINAL_DECISION);

        assertThat(a).isTrue();
        assertThat(b).isTrue();
        DailyOrchestrationStatusEntity e = store.get(today);
        assertThat(e.getStepPremarketNotify()).isEqualTo(DailyOrchestrationService.STATUS_DONE);
        assertThat(e.getStepFinalDecision()).isEqualTo(DailyOrchestrationService.STATUS_RUNNING);
    }

    // ── 5. markDone 後 markRunning → false ────────────────────────────────
    @Test
    void markDone_thenMarkRunning_shouldReturnFalse() {
        LocalDate today = LocalDate.of(2026, 4, 20);
        service.markRunning(today, OrchestrationStep.WATCHLIST_REFRESH);
        service.markDone(today, OrchestrationStep.WATCHLIST_REFRESH, "watchlist ok");

        boolean r = service.markRunning(today, OrchestrationStep.WATCHLIST_REFRESH);
        assertThat(r).isFalse();

        DailyOrchestrationStatusEntity e = store.get(today);
        assertThat(e.getStepWatchlistRefresh()).isEqualTo(DailyOrchestrationService.STATUS_DONE);
        assertThat(e.getNotes()).contains("watchlist ok");
    }

    // ── 6. markFailed 後 markRunning → true（允許重試）─────────────────────
    @Test
    void markFailed_thenMarkRunning_shouldAllowRetry() {
        LocalDate today = LocalDate.of(2026, 4, 20);
        service.markRunning(today, OrchestrationStep.T86_DATA_PREP);
        service.markFailed(today, OrchestrationStep.T86_DATA_PREP, "TWSE timeout");

        DailyOrchestrationStatusEntity e = store.get(today);
        assertThat(e.getStepT86DataPrep()).isEqualTo(DailyOrchestrationService.STATUS_FAILED);

        boolean retry = service.markRunning(today, OrchestrationStep.T86_DATA_PREP);
        assertThat(retry).isTrue();
        assertThat(store.get(today).getStepT86DataPrep())
                .isEqualTo(DailyOrchestrationService.STATUS_RUNNING);
    }

    // ── 額外：repeatable step（FiveMinute、Hourly）不會被 DONE 擋住 ───────
    @Test
    void markRunning_repeatableStep_shouldAlwaysReturnTrue() {
        LocalDate today = LocalDate.of(2026, 4, 20);
        // 先讓它被標為 DONE（模擬前一次 5 分鐘監控跑完）
        service.markRunning(today, OrchestrationStep.FIVE_MINUTE_MONITOR);
        service.markExecuted(today, OrchestrationStep.FIVE_MINUTE_MONITOR, "run 1");

        // repeatable → 下一次 markRunning 不該被擋
        boolean next = service.markRunning(today, OrchestrationStep.FIVE_MINUTE_MONITOR);
        assertThat(next).isTrue();
    }

    // ── 額外：forceMarkRunning 可覆寫 DONE ────────────────────────────────
    @Test
    void forceMarkRunning_onDoneStep_shouldOverride() {
        LocalDate today = LocalDate.of(2026, 4, 20);
        service.markRunning(today, OrchestrationStep.PREMARKET_NOTIFY);
        service.markDone(today, OrchestrationStep.PREMARKET_NOTIFY, "first run");

        boolean forced = service.forceMarkRunning(today, OrchestrationStep.PREMARKET_NOTIFY);
        assertThat(forced).isTrue();
        assertThat(store.get(today).getStepPremarketNotify())
                .isEqualTo(DailyOrchestrationService.STATUS_RUNNING);
    }

    // ── 額外：enforce_idempotency = false 時退回舊行為 ─────────────────────
    @Test
    void markRunning_idempotencyDisabled_shouldAlwaysReturnTrue() {
        LocalDate today = LocalDate.of(2026, 4, 20);
        when(config.getBoolean(eq("orchestration.enforce_idempotency"), anyBoolean()))
                .thenReturn(false);

        service.markRunning(today, OrchestrationStep.PREMARKET_NOTIFY);
        service.markDone(today, OrchestrationStep.PREMARKET_NOTIFY, "ok");
        // idempotency 關閉時，重跑也回 true
        boolean r = service.markRunning(today, OrchestrationStep.PREMARKET_NOTIFY);
        assertThat(r).isTrue();
    }

    // ── 額外：OrchestrationStep.fromKey 別名解析 ───────────────────────────
    @Test
    void orchestrationStep_fromKey_shouldResolveAliases() {
        assertThat(OrchestrationStep.fromKey("premarket")).contains(OrchestrationStep.PREMARKET_NOTIFY);
        assertThat(OrchestrationStep.fromKey("PremarketNotifyJob"))
                .contains(OrchestrationStep.PREMARKET_NOTIFY);
        assertThat(OrchestrationStep.fromKey("stepFinalDecision"))
                .contains(OrchestrationStep.FINAL_DECISION);
        assertThat(OrchestrationStep.fromKey("watchlist-refresh"))
                .contains(OrchestrationStep.WATCHLIST_REFRESH);
        assertThat(OrchestrationStep.fromKey("weekly-review"))
                .contains(OrchestrationStep.WEEKLY_TRADE_REVIEW);
        assertThat(OrchestrationStep.fromKey("unknown")).isEmpty();
        assertThat(OrchestrationStep.fromKey(null)).isEmpty();
    }

    // ── 額外：getStatusMap 對空紀錄回傳全 PENDING ──────────────────────────
    @Test
    void getStatusMap_noRow_shouldReturnAllPending() {
        LocalDate today = LocalDate.of(2026, 4, 21);
        Map<String, Object> m = service.getStatusMap(today);
        assertThat(m).containsEntry("stepPremarketNotify", DailyOrchestrationService.STATUS_PENDING);
        assertThat(m).containsEntry("stepFinalDecision", DailyOrchestrationService.STATUS_PENDING);
        assertThat(m).containsEntry("notes", null);
    }

    // ── 額外：save 驗證有被呼叫 ────────────────────────────────────────────
    @Test
    void markRunning_shouldInvokeRepositorySave() {
        LocalDate today = LocalDate.of(2026, 4, 20);
        service.markRunning(today, OrchestrationStep.PREMARKET_NOTIFY);
        ArgumentCaptor<DailyOrchestrationStatusEntity> captor =
                ArgumentCaptor.forClass(DailyOrchestrationStatusEntity.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getTradingDate()).isEqualTo(today);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helper：繞過 JPA 的 @Column(insertable=false, updatable=false)，
    //  直接塞 updated_at 值讓測試能模擬「stale」情境。
    // ─────────────────────────────────────────────────────────────────────
    private static void setUpdatedAt(DailyOrchestrationStatusEntity entity, LocalDateTime ts) {
        try {
            Field f = DailyOrchestrationStatusEntity.class.getDeclaredField("updatedAt");
            f.setAccessible(true);
            f.set(entity, ts);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
