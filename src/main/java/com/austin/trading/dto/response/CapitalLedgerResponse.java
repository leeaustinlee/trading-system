package com.austin.trading.dto.response;

import com.austin.trading.entity.CapitalLedgerEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record CapitalLedgerResponse(
        Long id,
        String ledgerType,
        BigDecimal amount,
        String symbol,
        Long positionId,
        LocalDate tradeDate,
        LocalDateTime occurredAt,
        String note,
        String source,
        String payloadJson,
        LocalDateTime createdAt
) {
    public static CapitalLedgerResponse from(CapitalLedgerEntity e) {
        return new CapitalLedgerResponse(
                e.getId(),
                e.getLedgerType() == null ? null : e.getLedgerType().name(),
                e.getAmount(),
                e.getSymbol(),
                e.getPositionId(),
                e.getTradeDate(),
                e.getOccurredAt(),
                e.getNote(),
                e.getSource(),
                e.getPayloadJson(),
                e.getCreatedAt()
        );
    }
}
