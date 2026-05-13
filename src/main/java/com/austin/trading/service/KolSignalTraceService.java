package com.austin.trading.service;

import com.austin.trading.entity.KolSignalTraceEntity;
import com.austin.trading.repository.KolSignalTraceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class KolSignalTraceService {

    private final KolSignalTraceRepository traceRepo;
    private final ObjectMapper objectMapper;

    public KolSignalTraceService(KolSignalTraceRepository traceRepo, ObjectMapper objectMapper) {
        this.traceRepo = traceRepo;
        this.objectMapper = objectMapper;
    }

    public KolSignalTraceEntity write(Long signalId, String stage, String action, Map<String, Object> detail) {
        KolSignalTraceEntity trace = new KolSignalTraceEntity();
        trace.setSignalId(signalId);
        trace.setTraceStage(stage);
        trace.setTraceAction(action);
        trace.setDetailJson(toJson(detail));
        return traceRepo.save(trace);
    }

    private String toJson(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"jsonError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }
}
