package com.austin.trading.service;

import com.austin.trading.repository.CandidateForwardTrackingRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class CandidateForwardTrackingService {
    private final CandidateForwardTrackingRepository repository;

    public CandidateForwardTrackingService(CandidateForwardTrackingRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> summary() {
        return Map.of("total", repository.count());
    }
    public List<Map<String, Object>> byDecision() { return repository.byDecision(); }
    public List<Map<String, Object>> byGrade() { return repository.byGrade(); }
    public List<Map<String, Object>> byStrategy() { return repository.byStrategy(); }
    public List<Map<String, Object>> byGate() { return repository.byGate(); }
}
