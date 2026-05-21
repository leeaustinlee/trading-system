package com.austin.trading.service;

import com.austin.trading.dto.response.NarrativeDashboardResponse;
import com.austin.trading.entity.KolThemeSignalDailySnapshotEntity;
import com.austin.trading.repository.KolThemeSignalDailySnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class NarrativeCandidateContextService {

    private final KolThemeSignalDailySnapshotRepository snapshotRepo;
    private final NarrativeDashboardService dashboardService;
    private final ObjectMapper objectMapper;

    public NarrativeCandidateContextService(KolThemeSignalDailySnapshotRepository snapshotRepo,
                                            NarrativeDashboardService dashboardService,
                                            ObjectMapper objectMapper) {
        this.snapshotRepo = snapshotRepo;
        this.dashboardService = dashboardService;
        this.objectMapper = objectMapper;
    }

    public String mergeIntoPayload(LocalDate tradingDate, String themeTag, String existingPayloadJson) {
        if (themeTag == null || themeTag.isBlank()) return existingPayloadJson;
        var snapshots = snapshotRepo.findByTradingDateAndThemeTag(tradingDate, themeTag);
        if (snapshots == null || snapshots.isEmpty()) return existingPayloadJson;
        KolThemeSignalDailySnapshotEntity selected = snapshots.stream()
                .filter(s -> "POSITIVE".equalsIgnoreCase(s.getDirection()))
                .findFirst()
                .orElse(snapshots.get(0));
        NarrativeDashboardResponse.Row row = dashboardService.toRow(selected);
        try {
            ObjectNode root = existingPayloadJson == null || existingPayloadJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : (ObjectNode) objectMapper.readTree(existingPayloadJson);
            ObjectNode ctx = objectMapper.createObjectNode();
            ctx.put("weakSignalOnly", true);
            ctx.put("theme", row.theme());
            ctx.put("lifecycle", row.lifecycle());
            ctx.put("attention", row.attention());
            ctx.put("crowding", row.crowding());
            ctx.put("direction", row.direction());
            ctx.put("sourceCount", row.sourceCount());
            ctx.put("evidenceCount", row.evidenceCount());
            ctx.put("shadowBoost", row.shadowBoost());
            ctx.put("guardrail", KolSignalContextService.WEAK_SIGNAL_GUARDRAIL);
            root.set("narrativeContext", ctx);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            return existingPayloadJson;
        }
    }
}
