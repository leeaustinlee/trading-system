package com.austin.trading.service;

import com.austin.trading.entity.MissedRallyTrackingEntity;
import com.austin.trading.repository.MissedRallyTrackingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Service
public class MissedRallyTrackingService {
    private final MissedRallyTrackingRepository repository;

    public MissedRallyTrackingService(MissedRallyTrackingRepository repository) {
        this.repository = repository;
    }

    public MissedRallyTrackingEntity save(MissedRallyTrackingEntity entity) {
        evaluateFlag(entity);
        return repository.save(entity);
    }

    public void evaluateFlag(MissedRallyTrackingEntity entity) {
        BigDecimal maxReturn = entity.getMaxReturnPct();
        if (maxReturn == null && entity.getCurrentPriceAtDecision() != null
                && entity.getCurrentPriceAtDecision().signum() > 0 && entity.getT5High() != null) {
            maxReturn = entity.getT5High().subtract(entity.getCurrentPriceAtDecision())
                    .divide(entity.getCurrentPriceAtDecision(), 4, java.math.RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            entity.setMaxReturnPct(maxReturn);
        }
        boolean missed = maxReturn != null && maxReturn.compareTo(new BigDecimal("8.0")) >= 0;
        entity.setMissedRallyFlag(missed);
        if (missed && entity.getMissedRallyReason() == null) {
            entity.setMissedRallyReason("T+5 maxReturnPct >= 8%");
        }
    }

    public List<MissedRallyTrackingEntity> recent(int limit) {
        return repository.findAllByOrderByTradingDateDescIdDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }

    public Map<String, Object> summary() {
        long total = repository.count();
        long missed = repository.findAll().stream().filter(e -> Boolean.TRUE.equals(e.getMissedRallyFlag())).count();
        return Map.of("total", total, "missed", missed);
    }

    public List<Map<String, Object>> byGate() { return repository.summaryByGate(); }
    public List<Map<String, Object>> byStrategy() { return repository.summaryByStrategy(); }
}
