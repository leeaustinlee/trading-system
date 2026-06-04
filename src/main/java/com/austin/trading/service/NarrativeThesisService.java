package com.austin.trading.service;

import com.austin.trading.dto.response.NarrativeThesisResponse;
import com.austin.trading.dto.response.PositionThesisLedgerResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NarrativeThesisService {
    private final PositionThesisLedgerService positionThesisLedgerService;

    public NarrativeThesisService(PositionThesisLedgerService positionThesisLedgerService) {
        this.positionThesisLedgerService = positionThesisLedgerService;
    }

    @Transactional(readOnly = true)
    public NarrativeThesisResponse openPositions() {
        PositionThesisLedgerResponse theses = positionThesisLedgerService.openTheses();
        return NarrativeThesisResponse.of(theses.items());
    }
}
