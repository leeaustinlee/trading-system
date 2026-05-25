package com.austin.trading.service;

import com.austin.trading.dto.response.KolThemeSnapshotResponse;
import com.austin.trading.engine.KolThemeSignalEngine;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.entity.KolThemeSignalEntity;
import com.austin.trading.entity.ThemeSignalEvidenceEntity;
import com.austin.trading.repository.KolSourceProfileRepository;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import com.austin.trading.repository.KolThemeSignalRepository;
import com.austin.trading.repository.ThemeSignalEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KolSignalAggregationService {
    private static final String AGGREGATION_VERSION = "mvp-rebuild-v1";

    private final KolThemeSignalEngine engine;
    private final ThemeSignalEvidenceRepository evidenceRepo;
    private final KolThemeSignalRepository signalRepo;
    private final KolSourceProfileRepository sourceProfileRepo;
    private final KolThemeSignalDailySnapshotRepository snapshotRepo;
    private final KolSignalTraceService traceService;
    private final ObjectMapper objectMapper;
    private final NarrativeMirrorService narrativeMirrorService;

    public KolSignalAggregationService(KolThemeSignalEngine engine,
                                       ThemeSignalEvidenceRepository evidenceRepo,
                                       KolThemeSignalRepository signalRepo,
                                       KolSourceProfileRepository sourceProfileRepo,
                                       KolThemeSignalDailySnapshotRepository snapshotRepo,
                                       KolSignalTraceService traceService,
                                       ObjectMapper objectMapper) {
        this(engine, evidenceRepo, signalRepo, sourceProfileRepo, snapshotRepo, traceService, objectMapper, null);
    }

    @Autowired
    public KolSignalAggregationService(KolThemeSignalEngine engine,
                                       ThemeSignalEvidenceRepository evidenceRepo,
                                       KolThemeSignalRepository signalRepo,
                                       KolSourceProfileRepository sourceProfileRepo,
                                       KolThemeSignalDailySnapshotRepository snapshotRepo,
                                       KolSignalTraceService traceService,
                                       ObjectMapper objectMapper,
                                       ObjectProvider<NarrativeMirrorService> narrativeMirrorProvider) {
        this.engine = engine;
        this.evidenceRepo = evidenceRepo;
        this.signalRepo = signalRepo;
        this.sourceProfileRepo = sourceProfileRepo;
        this.snapshotRepo = snapshotRepo;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.narrativeMirrorService = narrativeMirrorProvider == null ? null : narrativeMirrorProvider.getIfAvailable();
    }

    @Transactional
    public List<KolThemeSnapshotResponse> rebuildDailySnapshot(LocalDate date) {
        List<ThemeSignalEvidenceEntity> evidence = evidenceRepo.findByTradingDate(date);
        long deletedSnapshotCount = snapshotRepo.deleteByTradingDate(date);
        // Flush the delete before inserting rebuilt rows with the same
        // (trading_date, theme_tag, direction) unique key. Without this, Hibernate
        // may batch INSERTs before the derived delete is executed, causing a
        // duplicate-key failure on rebuild.
        snapshotRepo.flush();
        if (narrativeMirrorService != null) {
            narrativeMirrorService.clearDailySnapshots(date);
        }
        Map<Long, KolThemeSignalEntity> signals = signalRepo.findByTradingDateOrderByCreatedAtDesc(date).stream()
                .collect(Collectors.toMap(KolThemeSignalEntity::getId, Function.identity(), (a, b) -> a));
        Map<String, BigDecimal> sourceWeights = sourceProfileRepo.findAll().stream()
                .collect(Collectors.toMap(e -> e.getSourceKey(), e -> e.getCredibilityWeight() == null ? BigDecimal.ONE : e.getCredibilityWeight(), (a, b) -> a));

        Map<String, List<ThemeSignalEvidenceEntity>> groups = evidence.stream()
                .collect(Collectors.groupingBy(e -> e.getThemeTag() + "\u0000" + e.getDirection()));

        List<KolThemeSnapshotResponse> responses = new ArrayList<>();
        for (Map.Entry<String, List<ThemeSignalEvidenceEntity>> entry : groups.entrySet()) {
            ThemeSignalEvidenceEntity first = entry.getValue().get(0);
            List<KolThemeSignalEngine.EvidenceInput> inputs = entry.getValue().stream()
                    .map(e -> {
                        KolThemeSignalEntity signal = signals.get(e.getSignalId());
                        String sourceKey = signal == null ? "UNKNOWN" : signal.getSourceKey();
                        return new KolThemeSignalEngine.EvidenceInput(
                                sourceKey,
                                e.getEvidenceType(),
                                e.getDirection(),
                                e.getConfidence(),
                                sourceWeights.getOrDefault(sourceKey, BigDecimal.ONE)
                        );
                    })
                    .toList();
            KolThemeSignalEngine.SnapshotCalculation calc =
                    engine.calculate(first.getThemeTag(), first.getDirection(), inputs);
            KolThemeSignalDailySnapshotEntity snapshot = new KolThemeSignalDailySnapshotEntity();
            snapshot.setTradingDate(date);
            snapshot.setThemeTag(calc.themeTag());
            snapshot.setDirection(calc.direction());
            snapshot.setSourceCount(calc.sourceCount());
            snapshot.setEvidenceCount(calc.evidenceCount());
            snapshot.setPositiveScore(calc.positiveScore());
            snapshot.setNegativeScore(calc.negativeScore());
            snapshot.setNetShadowBoost(calc.netShadowBoost());
            snapshot.setCrowdingRisk(calc.crowdingRisk());
            snapshot.setTopSourcesJson(toJson(calc.topSources()));
            snapshot.setPayloadJson(toJson(calc.trace()));
            KolThemeSignalDailySnapshotEntity savedSnapshot = snapshotRepo.save(snapshot);
            responses.add(toResponse(savedSnapshot));
            if (narrativeMirrorService != null) {
                narrativeMirrorService.mirrorDailySnapshot(savedSnapshot);
            }
        }
        List<KolThemeSnapshotResponse> sorted = responses.stream()
                .sorted(Comparator.comparing(KolThemeSnapshotResponse::netShadowBoost).reversed())
                .toList();
        traceService.write(null, "AGGREGATION", "DAILY_REBUILT", Map.of(
                "date", date.toString(),
                "aggregationVersion", AGGREGATION_VERSION,
                "snapshotCount", sorted.size(),
                "deletedSnapshotCount", deletedSnapshotCount,
                "weakSignalOnly", true
        ));
        return sorted;
    }

    public List<KolThemeSnapshotResponse> getSnapshots(LocalDate date) {
        return snapshotRepo.findByTradingDateOrderByNetShadowBoostDesc(date).stream()
                .map(this::toResponse)
                .toList();
    }

    public List<KolThemeSnapshotResponse> getTheme(LocalDate date, String themeTag) {
        return snapshotRepo.findByTradingDateAndThemeTag(date, themeTag).stream()
                .map(this::toResponse)
                .toList();
    }

    public KolThemeSnapshotResponse toResponse(KolThemeSignalDailySnapshotEntity e) {
        return new KolThemeSnapshotResponse(e.getId(), e.getTradingDate(), e.getThemeTag(), e.getDirection(),
                e.getSourceCount(), e.getEvidenceCount(), e.getPositiveScore(), e.getNegativeScore(),
                e.getNetShadowBoost(), e.getCrowdingRisk(), e.getTopSourcesJson(), e.getPayloadJson());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "[]";
        }
    }
}
