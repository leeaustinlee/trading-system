package com.austin.trading.service;

import com.austin.trading.entity.SystemBuildTraceEntity;
import com.austin.trading.repository.SystemBuildTraceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class SystemBuildTraceService {
    private final SystemBuildTraceRepository repository;
    private final ObjectMapper objectMapper;

    public SystemBuildTraceService(SystemBuildTraceRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemBuildTraceEntity start(String buildType, LocalDate date, String sourcePhase, Object safetyBoundary) {
        SystemBuildTraceEntity e = new SystemBuildTraceEntity();
        e.setBuildType(buildType);
        e.setTradingDate(date);
        e.setSourcePhase(sourcePhase);
        e.setStartedAt(LocalDateTime.now());
        e.setStatus("PARTIAL");
        e.setSafetyBoundaryJson(toJson(safetyBoundary));
        return repository.saveAndFlush(e);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemBuildTraceEntity success(Long id, int deleted, int inserted, int updated, int skipped, Map<String, Object> payload) {
        SystemBuildTraceEntity e = repository.findById(id).orElseThrow();
        finish(e, "SUCCESS");
        e.setDeletedCount(deleted);
        e.setInsertedCount(inserted);
        e.setUpdatedCount(updated);
        e.setSkippedCount(skipped);
        e.setPayloadJson(toJson(payload));
        return repository.saveAndFlush(e);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SystemBuildTraceEntity failed(Long id, int deleted, int inserted, int skipped, Throwable error, Map<String, Object> payload) {
        SystemBuildTraceEntity e = repository.findById(id).orElseThrow();
        finish(e, "FAILED");
        e.setDeletedCount(deleted);
        e.setInsertedCount(inserted);
        e.setSkippedCount(skipped);
        e.setErrorMessage(trim(error == null ? null : error.getMessage()));
        e.setPayloadJson(toJson(payload));
        return repository.saveAndFlush(e);
    }

    private void finish(SystemBuildTraceEntity e, String status) {
        LocalDateTime finished = LocalDateTime.now();
        if (finished.isBefore(e.getStartedAt())) {
            finished = e.getStartedAt();
        }
        e.setFinishedAt(finished);
        e.setDurationMs(Duration.between(e.getStartedAt(), finished).toMillis());
        e.setStatus(status);
    }

    private String trim(String s) {
        if (s == null) return null;
        return s.length() <= 2000 ? s : s.substring(0, 2000);
    }

    private String toJson(Object value) {
        if (value == null) return "{}";
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { return "{\"serializationError\":true}"; }
    }
}
