package com.austin.trading.service;

import com.austin.trading.dto.request.KolSignalCreateRequest;
import com.austin.trading.dto.response.KolSignalResponse;
import com.austin.trading.entity.KolThemeSignalEntity;
import com.austin.trading.repository.KolThemeSignalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class KolSignalIngestionService {

    public static final int RAW_CONTENT_LIMIT = 64 * 1024;

    private final KolThemeSignalRepository signalRepo;
    private final KolSignalTraceService traceService;
    private final ObjectMapper objectMapper;
    private final NarrativeMirrorService narrativeMirrorService;

    public KolSignalIngestionService(KolThemeSignalRepository signalRepo,
                                     KolSignalTraceService traceService,
                                     ObjectMapper objectMapper) {
        this(signalRepo, traceService, objectMapper, null);
    }

    @Autowired
    public KolSignalIngestionService(KolThemeSignalRepository signalRepo,
                                     KolSignalTraceService traceService,
                                     ObjectMapper objectMapper,
                                     ObjectProvider<NarrativeMirrorService> narrativeMirrorProvider) {
        this.signalRepo = signalRepo;
        this.traceService = traceService;
        this.objectMapper = objectMapper;
        this.narrativeMirrorService = narrativeMirrorProvider == null ? null : narrativeMirrorProvider.getIfAvailable();
    }

    @Transactional
    public KolSignalResponse create(KolSignalCreateRequest request) {
        String raw = request.rawContent() == null ? "" : request.rawContent();
        String hash = sha256(raw.strip());
        var existing = signalRepo.findByContentHash(hash);
        if (existing.isPresent()) {
            KolThemeSignalEntity e = existing.get();
            traceService.write(e.getId(), "INGEST", "DEDUP_HIT", Map.of("contentHash", hash));
            return toResponse(e, true, payloadFlag(e.getPayloadJson(), "rawContentTruncated"));
        }

        boolean truncated = raw.length() > RAW_CONTENT_LIMIT;
        KolThemeSignalEntity entity = new KolThemeSignalEntity();
        entity.setTradingDate(request.tradingDate() != null ? request.tradingDate() : LocalDate.now());
        entity.setSourceKey(required(request.sourceKey(), "sourceKey"));
        entity.setSourceType(defaulted(request.sourceType(), "MANUAL"));
        entity.setSourceTitle(request.sourceTitle());
        entity.setContentHash(hash);
        entity.setRawContent(truncated ? raw.substring(0, RAW_CONTENT_LIMIT) : raw);
        entity.setSignalStatus("RAW");

        Map<String, Object> payload = new LinkedHashMap<>();
        if (request.payload() != null) payload.putAll(request.payload());
        payload.put("rawContentTruncated", truncated);
        payload.put("rawContentOriginalLength", raw.length());
        payload.put("weakSignalOnly", true);
        entity.setPayloadJson(toJson(payload));

        KolThemeSignalEntity saved = signalRepo.save(entity);
        traceService.write(saved.getId(), "INGEST", "RAW_CREATED", Map.of(
                "contentHash", hash,
                "rawContentTruncated", truncated,
                "weakSignalOnly", true
        ));
        if (narrativeMirrorService != null) {
            narrativeMirrorService.mirrorRawSignal(saved);
        }
        return toResponse(saved, false, truncated);
    }

    public KolSignalResponse toResponse(KolThemeSignalEntity e, boolean duplicate, boolean truncated) {
        return new KolSignalResponse(e.getId(), e.getTradingDate(), e.getSourceKey(), e.getSourceType(),
                e.getSourceTitle(), e.getSignalStatus(), e.getContentHash(), duplicate, truncated, e.getCreatedAt());
    }

    private boolean payloadFlag(String payloadJson, String key) {
        try {
            return payloadJson != null && objectMapper.readTree(payloadJson).path(key).asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String defaulted(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
