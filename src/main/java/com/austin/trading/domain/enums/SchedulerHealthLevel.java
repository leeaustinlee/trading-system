package com.austin.trading.domain.enums;

/**
 * Truth-level result for scheduled jobs.
 *
 * <p>Legacy {@code status} still answers "did the Java method throw?".
 * This level answers "how usable was the produced data/output?" so a job that
 * ran without exception can still be EMPTY_DATA, DEGRADED, SKIPPED, or
 * SUCCESS_WITH_FALLBACK instead of being mistaken for a clean success.</p>
 */
public enum SchedulerHealthLevel {
    SUCCESS_REAL,
    SUCCESS_WITH_FALLBACK,
    DEGRADED,
    EMPTY_DATA,
    SKIPPED,
    FAILED
}
