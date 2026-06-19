package com.austin.trading.service;

import com.austin.trading.dto.internal.ThemeAdmissionWriteSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class ThemeAdmissionRebuildService {

    private final ThemeAdmissionShadowService shadowService;
    private final ThemeAdmissionWriteService writeService;
    private final boolean writeEnabled;
    private final boolean buyImpactEnabled;

    public ThemeAdmissionRebuildService(ThemeAdmissionShadowService shadowService,
                                        ThemeAdmissionWriteService writeService,
                                        @Value("${trading.theme-admission.write-enabled:false}") boolean writeEnabled,
                                        @Value("${trading.theme-admission.buy-impact-enabled:false}") boolean buyImpactEnabled) {
        this.shadowService = shadowService;
        this.writeService = writeService;
        this.writeEnabled = writeEnabled;
        this.buyImpactEnabled = buyImpactEnabled;
    }

    @Transactional
    public Result rebuild(LocalDate start, LocalDate end, boolean write) {
        if (write && !writeEnabled) {
            throw new IllegalStateException("theme admission write requested but trading.theme-admission.write-enabled=false");
        }
        if (write && buyImpactEnabled) {
            throw new IllegalStateException("trading.theme-admission.buy-impact-enabled must remain false for P1-A");
        }
        int processedDays = 0;
        int shadowRows = 0;
        Accumulator acc = new Accumulator();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            shadowRows += shadowService.rebuildForDate(date);
            if (write) {
                acc.add(writeService.rebuildForDate(date));
            }
            processedDays++;
        }
        return new Result(start, end, processedDays, shadowRows,
                write ? acc.toSummary() : ThemeAdmissionWriteSummary.empty(), !write, false);
    }

    public record Result(
            LocalDate startDate,
            LocalDate endDate,
            int processedDays,
            int shadowRows,
            ThemeAdmissionWriteSummary writeSummary,
            boolean shadowOnly,
            boolean productionBuyImpact
    ) { }

    private static class Accumulator {
        int processedSignals;
        int admittedCandidates;
        int admittedWatchlists;
        int skippedLimitRisk;
        int skippedAlreadyExists;
        int rejectedBadData;
        int rejectedLiquidity;
        int rejectedWeakTheme;
        int shadowOnly;

        void add(ThemeAdmissionWriteSummary s) {
            processedSignals += s.processedSignals();
            admittedCandidates += s.admittedCandidates();
            admittedWatchlists += s.admittedWatchlists();
            skippedLimitRisk += s.skippedLimitRisk();
            skippedAlreadyExists += s.skippedAlreadyExists();
            rejectedBadData += s.rejectedBadData();
            rejectedLiquidity += s.rejectedLiquidity();
            rejectedWeakTheme += s.rejectedWeakTheme();
            shadowOnly += s.shadowOnly();
        }

        ThemeAdmissionWriteSummary toSummary() {
            return new ThemeAdmissionWriteSummary(processedSignals, admittedCandidates, admittedWatchlists,
                    skippedLimitRisk, skippedAlreadyExists, rejectedBadData, rejectedLiquidity,
                    rejectedWeakTheme, shadowOnly, false);
        }
    }
}
