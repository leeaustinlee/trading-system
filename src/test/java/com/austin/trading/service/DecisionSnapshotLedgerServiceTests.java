package com.austin.trading.service;

import com.austin.trading.dto.response.FinalDecisionResponse;
import com.austin.trading.dto.response.FinalDecisionSelectedStockResponse;
import com.austin.trading.entity.DecisionSnapshotLedgerEntity;
import com.austin.trading.entity.FinalDecisionEntity;
import com.austin.trading.repository.DecisionSnapshotLedgerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DecisionSnapshotLedgerServiceTests {

    @Test
    void safeCreateFromFinalDecision_buildsAndPersistsLedger() {
        DecisionSnapshotLedgerRepository repository = mock(DecisionSnapshotLedgerRepository.class);
        when(repository.save(any(DecisionSnapshotLedgerEntity.class))).thenAnswer(invocation -> {
            DecisionSnapshotLedgerEntity entity = invocation.getArgument(0);
            entity.setId(99L);
            return entity;
        });
        DecisionSnapshotLedgerService service = new DecisionSnapshotLedgerService(
                repository, new ObjectMapper(), transactionManager());

        FinalDecisionEntity finalDecision = finalDecision();
        FinalDecisionResponse response = new FinalDecisionResponse(
                "ENTER",
                List.of(new FinalDecisionSelectedStockResponse(
                        "2330", "TSMC", "BREAKOUT", "600-610",
                        580.0, 650.0, 700.0, 2.0, "ok", 0.5, 1.0)),
                List.of("NO_TIMING"),
                "summary",
                Map.of("decisionTrace", Map.of("marketGrade", "A", "priceGate", "PASS")));

        var saved = service.safeCreateFromFinalDecision(finalDecision, response,
                new FinalDecisionService.AiReadiness(
                        FinalDecisionService.AiReadinessMode.FULL_AI_READY,
                        77L, "OPENING", null, null, null),
                "OPENING");

        assertThat(saved).isPresent();
        ArgumentCaptor<DecisionSnapshotLedgerEntity> captor = ArgumentCaptor.forClass(DecisionSnapshotLedgerEntity.class);
        verify(repository).save(captor.capture());
        DecisionSnapshotLedgerEntity entity = captor.getValue();
        assertThat(entity.getFinalDecisionId()).isEqualTo(42L);
        assertThat(entity.getTradingDate()).isEqualTo(LocalDate.of(2026, 5, 18));
        assertThat(entity.getSourceTaskType()).isEqualTo("OPENING");
        assertThat(entity.getPreferTaskType()).isEqualTo("OPENING");
        assertThat(entity.getAiTaskId()).isEqualTo(77L);
        assertThat(entity.getAiReadinessMode()).isEqualTo("FULL_AI_READY");
        assertThat(entity.getFinalDecisionCode()).isEqualTo("ENTER");
        assertThat(entity.getSelectedSymbolsJson()).contains("2330");
        assertThat(entity.getMergedSymbolsJson()).contains("2330");
        assertThat(entity.getRejectedSymbolsJson()).isEqualTo("[]");
        assertThat(entity.getResponsePayloadJson()).contains("decisionTrace");
    }

    @Test
    void buildEntity_doesNotTreatSelectedStocksAsCandidateUniverse() {
        DecisionSnapshotLedgerService service = new DecisionSnapshotLedgerService(
                mock(DecisionSnapshotLedgerRepository.class), new ObjectMapper(), transactionManager());

        DecisionSnapshotLedgerEntity entity = service.buildEntity(finalDecision(),
                new FinalDecisionResponse("ENTER", List.of(), List.of(), "summary"),
                null, null);

        assertThat(entity.getCandidateUniverseJson()).isNull();
        assertThat(entity.getCandidateScoresJson()).isNull();
    }

    @Test
    void buildEntity_extractsExistingCandidateUniverseKeyOnlyWhenPresent() {
        DecisionSnapshotLedgerService service = new DecisionSnapshotLedgerService(
                mock(DecisionSnapshotLedgerRepository.class), new ObjectMapper(), transactionManager());
        FinalDecisionEntity finalDecision = finalDecision();
        finalDecision.setPayloadJson("{\"planning\":{\"candidateUniverse\":[{\"stockCode\":\"2317\",\"finalRankScore\":88}],\"decisionTrace\":{\"marketGrade\":\"B\"}}}");

        DecisionSnapshotLedgerEntity entity = service.buildEntity(finalDecision,
                new FinalDecisionResponse("ENTER", List.of(), List.of(), "summary"),
                null, null);

        assertThat(entity.getCandidateUniverseJson()).contains("2317");
        assertThat(entity.getCandidateScoresJson()).contains("finalRankScore");
    }

    @Test
    void scheduleCreateAfterCommit_withoutActiveTransactionWritesImmediatelyThroughSafePath() {
        DecisionSnapshotLedgerRepository repository = mock(DecisionSnapshotLedgerRepository.class);
        when(repository.save(any(DecisionSnapshotLedgerEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        DecisionSnapshotLedgerService service = new DecisionSnapshotLedgerService(
                repository, new ObjectMapper(), transactionManager());

        service.scheduleCreateAfterCommit(finalDecision(),
                new FinalDecisionResponse("REST", List.of(), List.of("AI_NOT_READY"), "rest"),
                null, null);

        verify(repository).save(any(DecisionSnapshotLedgerEntity.class));
    }

    @Test
    void scheduleCreateAfterCommit_insideActiveTransactionDefersUntilCommit() {
        DecisionSnapshotLedgerRepository repository = mock(DecisionSnapshotLedgerRepository.class);
        when(repository.save(any(DecisionSnapshotLedgerEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        PlatformTransactionManager txManager = transactionManager();
        DecisionSnapshotLedgerService service = new DecisionSnapshotLedgerService(
                repository, new ObjectMapper(), txManager);
        TransactionTemplate tx = new TransactionTemplate(txManager);

        tx.executeWithoutResult(status -> {
            service.scheduleCreateAfterCommit(finalDecision(),
                    new FinalDecisionResponse("REST", List.of(), List.of("AI_NOT_READY"), "rest"),
                    null, null);
            verify(repository, never()).save(any(DecisionSnapshotLedgerEntity.class));
        });

        verify(repository, times(1)).save(any(DecisionSnapshotLedgerEntity.class));
    }

    @Test
    void safeCreateFromFinalDecision_swallowsRepositoryFailure() {
        DecisionSnapshotLedgerRepository repository = mock(DecisionSnapshotLedgerRepository.class);
        when(repository.save(any(DecisionSnapshotLedgerEntity.class))).thenThrow(new RuntimeException("db down"));
        DecisionSnapshotLedgerService service = new DecisionSnapshotLedgerService(
                repository, new ObjectMapper(), transactionManager());

        assertThatCode(() -> service.safeCreateFromFinalDecision(finalDecision(),
                new FinalDecisionResponse("REST", List.of(), List.of("AI_NOT_READY"), "rest"),
                null, null)).doesNotThrowAnyException();
    }

    private FinalDecisionEntity finalDecision() {
        FinalDecisionEntity entity = new FinalDecisionEntity();
        ReflectionTestUtils.setField(entity, "id", 42L);
        entity.setTradingDate(LocalDate.of(2026, 5, 18));
        entity.setDecision("ENTER");
        entity.setSourceTaskType("OPENING");
        entity.setAiTaskId(77L);
        entity.setAiStatus("FULL_AI_READY");
        entity.setPayloadJson("{\"decision\":\"ENTER\",\"planning\":{\"decisionTrace\":{\"marketGrade\":\"A\",\"priceGate\":\"PASS\"}}}");
        return entity;
    }

    private PlatformTransactionManager transactionManager() {
        return new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
    }
}
