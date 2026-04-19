package com.austin.trading.service;

import com.austin.trading.entity.DailyOrchestrationStatusEntity;
import com.austin.trading.repository.DailyOrchestrationStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日排程狀態服務（PR-1 防重跑 + 補跑）。
 *
 * <p>核心職責：</p>
 * <ul>
 *     <li>{@link #markRunning(LocalDate, OrchestrationStep)}：
 *         於 DB 層用悲觀鎖進行原子更新；若該 step 今日已 DONE 則回傳 false，
 *         scheduler 可據此判斷是否要 skip。</li>
 *     <li>{@link #markDone(LocalDate, OrchestrationStep, String)}：標記完成、寫入 notes。</li>
 *     <li>{@link #markFailed(LocalDate, OrchestrationStep, String)}：標記失敗並附上錯誤訊息。</li>
 *     <li>{@link #forceMarkRunning(LocalDate, OrchestrationStep)}：手動觸發 {@code ?force=true} 時使用，
 *         覆寫 DONE 狀態。</li>
 *     <li>{@link #markExecuted(LocalDate, OrchestrationStep, String)}：
 *         for 一天跑多次的 intraday job，不做 DONE 阻擋，只更新狀態與 notes。</li>
 * </ul>
 */
@Service
public class DailyOrchestrationService {

    public static final String STATUS_PENDING  = "PENDING";
    public static final String STATUS_RUNNING  = "RUNNING";
    public static final String STATUS_DONE     = "DONE";
    public static final String STATUS_FAILED   = "FAILED";
    public static final String STATUS_SKIPPED  = "SKIPPED";

    private static final int DEFAULT_STALE_MINUTES = 15;
    private static final int NOTES_MAX_LENGTH = 4000;

    private static final Logger log = LoggerFactory.getLogger(DailyOrchestrationService.class);

    private final DailyOrchestrationStatusRepository repository;
    private final ScoreConfigService scoreConfig;

    public DailyOrchestrationService(
            DailyOrchestrationStatusRepository repository,
            ScoreConfigService scoreConfig
    ) {
        this.repository = repository;
        this.scoreConfig = scoreConfig;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  公開 API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 嘗試將 step 標記為 RUNNING。
     *
     * <ul>
     *     <li>若 row 不存在 → 建立並設為 RUNNING，回傳 true。</li>
     *     <li>若該 step 狀態 = DONE 且非 repeatable → 回傳 false（今日已跑過，skip）。</li>
     *     <li>若該 step 狀態 = RUNNING 且 {@code updated_at} 超過 stale 分鐘 → 視為卡死，覆蓋為 RUNNING 回傳 true。</li>
     *     <li>若該 step 狀態 = RUNNING 且尚未 stale → 回傳 false（避免 scheduler 重跑撞上）。</li>
     *     <li>其他狀態（PENDING/FAILED/SKIPPED、或 repeatable step）→ 設 RUNNING，回傳 true。</li>
     * </ul>
     *
     * <p>若 {@code orchestration.enforce_idempotency} = false 則退回舊行為：永遠回傳 true。</p>
     */
    @Transactional
    public boolean markRunning(LocalDate date, OrchestrationStep step) {
        if (!isIdempotencyEnforced()) {
            applyStatus(date, step, STATUS_RUNNING, null);
            return true;
        }

        DailyOrchestrationStatusEntity entity = repository.findForUpdate(date).orElse(null);
        if (entity == null) {
            entity = new DailyOrchestrationStatusEntity();
            entity.setTradingDate(date);
            writeField(entity, step, STATUS_RUNNING);
            repository.save(entity);
            return true;
        }

        // repeatable step（FiveMinute / Hourly / ExternalProbe）不做 DONE 阻擋
        if (step.isRepeatable()) {
            writeField(entity, step, STATUS_RUNNING);
            repository.save(entity);
            return true;
        }

        String current = readField(entity, step);
        int staleMinutes = getStaleMinutes();

        if (STATUS_DONE.equalsIgnoreCase(current)) {
            return false;
        }

        if (STATUS_RUNNING.equalsIgnoreCase(current)) {
            LocalDateTime updatedAt = entity.getUpdatedAt();
            boolean stale = updatedAt == null
                    || Duration.between(updatedAt, LocalDateTime.now()).toMinutes() >= staleMinutes;
            if (stale) {
                log.warn("[DailyOrchestration] {} {} stale RUNNING (>= {} min) — override.",
                        date, step.entityField(), staleMinutes);
                writeField(entity, step, STATUS_RUNNING);
                repository.save(entity);
                return true;
            }
            return false;
        }

        // PENDING / FAILED / SKIPPED → 允許重試
        writeField(entity, step, STATUS_RUNNING);
        repository.save(entity);
        return true;
    }

    /**
     * 手動觸發時使用，強制將 step 設為 RUNNING 並覆寫 DONE。
     */
    @Transactional
    public boolean forceMarkRunning(LocalDate date, OrchestrationStep step) {
        applyStatus(date, step, STATUS_RUNNING, null);
        return true;
    }

    @Transactional
    public void markDone(LocalDate date, OrchestrationStep step, String notes) {
        applyStatus(date, step, STATUS_DONE, notes);
    }

    @Transactional
    public void markFailed(LocalDate date, OrchestrationStep step, String errorMessage) {
        applyStatus(date, step, STATUS_FAILED, errorMessage);
    }

    /**
     * 給一天執行多次的 intraday step（FiveMinute、Hourly、ExternalProbe）使用：
     * 不檢查 DONE，僅更新狀態為 DONE + 最新的 notes，讓 {@code updated_at} 自動刷新。
     */
    @Transactional
    public void markExecuted(LocalDate date, OrchestrationStep step, String notes) {
        applyStatus(date, step, STATUS_DONE, notes);
    }

    @Transactional(readOnly = true)
    public DailyOrchestrationStatusEntity getToday() {
        return getByDate(LocalDate.now());
    }

    @Transactional(readOnly = true)
    public DailyOrchestrationStatusEntity getByDate(LocalDate date) {
        return repository.findByTradingDate(date).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<DailyOrchestrationStatusEntity> getRecent(int days) {
        int n = Math.max(1, Math.min(days, 30));
        List<DailyOrchestrationStatusEntity> top5 = repository.findTop5ByOrderByTradingDateDesc();
        if (n <= 5) {
            return top5.subList(0, Math.min(n, top5.size()));
        }
        return repository.findAll().stream()
                .sorted((a, b) -> b.getTradingDate().compareTo(a.getTradingDate()))
                .limit(n)
                .toList();
    }

    /**
     * 取指定日期每個 step 的狀態 map（供 controller 輸出 JSON）。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStatusMap(LocalDate date) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tradingDate", date.toString());
        DailyOrchestrationStatusEntity entity = getByDate(date);
        if (entity == null) {
            for (OrchestrationStep s : OrchestrationStep.values()) {
                out.put(s.entityField(), STATUS_PENDING);
            }
            out.put("notes", null);
            out.put("updatedAt", null);
            return out;
        }
        for (OrchestrationStep s : OrchestrationStep.values()) {
            out.put(s.entityField(), defaultIfNull(readField(entity, s), STATUS_PENDING));
        }
        out.put("notes", entity.getNotes());
        out.put("updatedAt", entity.getUpdatedAt());
        return out;
    }

    /** 列出目前狀態非 DONE 的 step，按 enum 順序。 */
    @Transactional(readOnly = true)
    public List<OrchestrationStep> listIncompleteSteps(LocalDate date) {
        DailyOrchestrationStatusEntity entity = getByDate(date);
        return java.util.Arrays.stream(OrchestrationStep.values())
                .filter(s -> {
                    String v = entity == null ? null : readField(entity, s);
                    return v == null || !STATUS_DONE.equalsIgnoreCase(v);
                })
                .toList();
    }

    /**
     * 啟動健康檢查專用：將超過 staleMinutes 的 RUNNING 狀態標記為 FAILED。
     * 回傳被修正的 "date:stepField" 清單。
     */
    @Transactional
    public List<String> sweepStaleRunning(int staleMinutes, int recentDays) {
        List<String> fixed = new java.util.ArrayList<>();
        List<DailyOrchestrationStatusEntity> recent = getRecent(recentDays);
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(staleMinutes);
        for (DailyOrchestrationStatusEntity e : recent) {
            if (e.getUpdatedAt() == null || e.getUpdatedAt().isAfter(threshold)) {
                // 整筆都算新，略過
                continue;
            }
            boolean changed = false;
            for (OrchestrationStep s : OrchestrationStep.values()) {
                if (STATUS_RUNNING.equalsIgnoreCase(readField(e, s))) {
                    writeField(e, s, STATUS_FAILED);
                    fixed.add(e.getTradingDate() + ":" + s.entityField());
                    changed = true;
                }
            }
            if (changed) {
                String note = appendNote(e.getNotes(),
                        "[StartupSweep " + LocalDateTime.now() + "] stale RUNNING → FAILED");
                e.setNotes(note);
                repository.save(e);
            }
        }
        return fixed;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  私有方法
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 通用 upsert：若 row 不存在則建立，更新指定 step 的狀態與 notes。
     * 呼叫者需已在交易中（本 class 的 {@code @Transactional} 方法）。
     */
    private void applyStatus(LocalDate date, OrchestrationStep step, String status, String notes) {
        DailyOrchestrationStatusEntity entity = repository.findForUpdate(date).orElse(null);
        if (entity == null) {
            entity = new DailyOrchestrationStatusEntity();
            entity.setTradingDate(date);
        }
        writeField(entity, step, status);
        if (notes != null && !notes.isBlank()) {
            String prefix = step.entityField() + "=" + status + ": ";
            entity.setNotes(appendNote(entity.getNotes(), prefix + truncate(notes, 1500)));
        }
        repository.save(entity);
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Reflection helpers: step → entity getter/setter
    // ─────────────────────────────────────────────────────────────────────

    private String readField(DailyOrchestrationStatusEntity entity, OrchestrationStep step) {
        try {
            String getter = "get" + capitalize(step.entityField());
            Method m = DailyOrchestrationStatusEntity.class.getMethod(getter);
            Object v = m.invoke(entity);
            return v == null ? null : v.toString();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("無法讀取欄位 " + step.entityField(), e);
        }
    }

    private void writeField(DailyOrchestrationStatusEntity entity, OrchestrationStep step, String value) {
        try {
            String setter = "set" + capitalize(step.entityField());
            Method m = DailyOrchestrationStatusEntity.class.getMethod(setter, String.class);
            m.invoke(entity, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("無法寫入欄位 " + step.entityField(), e);
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String appendNote(String existing, String newLine) {
        if (existing == null || existing.isBlank()) return truncate(newLine, NOTES_MAX_LENGTH);
        String combined = existing + "\n" + newLine;
        return truncate(combined, NOTES_MAX_LENGTH);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private static String defaultIfNull(String v, String fallback) {
        return v == null ? fallback : v;
    }

    // ─────────────────────────────────────────────────────────────────────
    //  ScoreConfig helpers
    // ─────────────────────────────────────────────────────────────────────

    private int getStaleMinutes() {
        if (scoreConfig == null) return DEFAULT_STALE_MINUTES;
        return scoreConfig.getInt("orchestration.stale_running_minutes", DEFAULT_STALE_MINUTES);
    }

    private boolean isIdempotencyEnforced() {
        if (scoreConfig == null) return true;
        return scoreConfig.getBoolean("orchestration.enforce_idempotency", true);
    }
}
