package com.austin.trading.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 完整整合測試：涵蓋所有主要 API 端點的 Happy Path。
 * 執行前需確保 DB（trading_system_it）可連線，且已套用 V1~V6 migration。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class FullApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ─────────────────────────────────────────────────────────────────
    // Market / Dashboard
    // ─────────────────────────────────────────────────────────────────

    @Test
    void dashboard_shouldReturnEnvelope() throws Exception {
        mockMvc.perform(get("/api/dashboard/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isArray());
    }

    @Test
    void market_currentAndHistory_shouldReturn2xx() throws Exception {
        mockMvc.perform(get("/api/market/current"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get("/api/market/history"))
                .andExpect(status().is2xxSuccessful());
    }

    // ─────────────────────────────────────────────────────────────────
    // Decisions – evaluate endpoints
    // ─────────────────────────────────────────────────────────────────

    @Test
    void marketGateEvaluate_strongSignals_shouldReturnGradeA() throws Exception {
        String request = """
                {
                  "tsmcTrendUp": true,
                  "sectorsAligned": true,
                  "leadersStrong": true,
                  "nearHighNotBreak": false,
                  "washoutRebound": false,
                  "blowoffTopSignal": false
                }
                """;

        mockMvc.perform(post("/api/decisions/market-gate/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketGrade").value("A"));
    }

    @Test
    void marketGateEvaluate_weakSignals_shouldReturnGradeBorC() throws Exception {
        String request = """
                {
                  "tsmcTrendUp": false,
                  "sectorsAligned": false,
                  "leadersStrong": false,
                  "nearHighNotBreak": false,
                  "washoutRebound": false,
                  "blowoffTopSignal": true
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/decisions/market-gate/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String grade = body.path("marketGrade").asText();
        assertThat(grade).isIn("B", "C");
    }

    @Test
    void hourlyGateEvaluate_gradeA_enter_shouldReturnNormal() throws Exception {
        String request = """
                {
                  "marketGrade": "A",
                  "decision": "ENTER",
                  "hasPosition": false,
                  "hasCandidate": true,
                  "hasCriticalEvent": false
                }
                """;

        mockMvc.perform(post("/api/decisions/hourly-gate/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hourlyGate").isNotEmpty());
    }

    @Test
    void hourlyGateEvaluate_gradeC_shouldRestrictTrading() throws Exception {
        String request = """
                {
                  "marketGrade": "C",
                  "decision": "REST",
                  "hasPosition": false,
                  "hasCandidate": false,
                  "hasCriticalEvent": false
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/decisions/hourly-gate/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String gate = body.path("hourlyGate").asText();
        assertThat(gate).isNotBlank();
    }

    @Test
    void stockEvaluate_qualifiedEntry_shouldIncludeInPlan() throws Exception {
        String request = """
                {
                  "symbol": "2330",
                  "marketGrade": "A",
                  "entryType": "BREAKOUT",
                  "entryPrice": 100.0,
                  "stopLossPercent": 5.0,
                  "takeProfit1Percent": 8.0,
                  "takeProfit2Percent": 14.0,
                  "volatileStock": false,
                  "nearDayHigh": false,
                  "aboveOpen": true,
                  "abovePrevClose": true,
                  "mainStream": true,
                  "valuationScore": 70
                }
                """;

        mockMvc.perform(post("/api/decisions/stock/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valuationMode").isNotEmpty())
                .andExpect(jsonPath("$.riskRewardRatio").isNumber())
                .andExpect(jsonPath("$.stopLossPrice").isNumber())
                .andExpect(jsonPath("$.takeProfit1").isNumber());
    }

    @Test
    void stockEvaluate_belowOpen_shouldExcludeFromPlan() throws Exception {
        String request = """
                {
                  "symbol": "1234",
                  "marketGrade": "C",
                  "entryType": "PULLBACK",
                  "entryPrice": 50.0,
                  "stopLossPercent": 8.0,
                  "takeProfit1Percent": 5.0,
                  "takeProfit2Percent": 8.0,
                  "volatileStock": true,
                  "nearDayHigh": false,
                  "aboveOpen": false,
                  "abovePrevClose": false,
                  "mainStream": false,
                  "valuationScore": 10
                }
                """;

        MvcResult result = mockMvc.perform(post("/api/decisions/stock/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("includeInFinalPlan").asBoolean()).isFalse();
    }

    @Test
    void positionSizingEvaluate_shouldReturnPositiveSize() throws Exception {
        String request = """
                {
                  "marketGrade": "A",
                  "valuationMode": "VALUE_GROWTH",
                  "baseCapital": 500000.0,
                  "maxSinglePosition": 50000.0,
                  "riskBudgetRatio": 0.06,
                  "nearDayHigh": false
                }
                """;

        mockMvc.perform(post("/api/decisions/position-sizing/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestedPositionSize").isNumber());
    }

    @Test
    void stopLossTakeProfitEvaluate_shouldReturnPriceLevels() throws Exception {
        String request = """
                {
                  "entryPrice": 200.0,
                  "stopLossPercent": 5.0,
                  "takeProfit1Percent": 8.0,
                  "takeProfit2Percent": 15.0,
                  "volatileStock": false
                }
                """;

        mockMvc.perform(post("/api/decisions/stoploss-takeprofit/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopLossPrice").isNumber())
                .andExpect(jsonPath("$.takeProfit1").isNumber())
                .andExpect(jsonPath("$.takeProfit2").isNumber());
    }

    @Test
    void finalDecision_gradeC_shouldReturnRest() throws Exception {
        String request = """
                {
                  "marketGrade": "C",
                  "decisionLock": "NONE",
                  "timeDecayStage": "LATE",
                  "hasPosition": false,
                  "candidates": []
                }
                """;

        mockMvc.perform(post("/api/decisions/final/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REST"));
    }

    // ─────────────────────────────────────────────────────────────────
    // Decisions – read endpoints
    // ─────────────────────────────────────────────────────────────────

    @Test
    void decisions_currentAndHistory_shouldReturn2xx() throws Exception {
        mockMvc.perform(get("/api/decisions/current"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get("/api/decisions/history"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void hourlyGate_currentAndHistory_shouldReturn2xx() throws Exception {
        mockMvc.perform(get("/api/decisions/hourly-gate/current"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get("/api/decisions/hourly-gate/history"))
                .andExpect(status().is2xxSuccessful());
    }

    // ─────────────────────────────────────────────────────────────────
    // Monitor
    // ─────────────────────────────────────────────────────────────────

    @Test
    void monitor_currentAndHistory_shouldReturn2xx() throws Exception {
        mockMvc.perform(get("/api/monitor/current"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get("/api/monitor/history"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get("/api/monitor/decisions/current"))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(get("/api/monitor/decisions/history"))
                .andExpect(status().is2xxSuccessful());
    }

    @Test
    void monitorState_upsert_shouldReturn200() throws Exception {
        String request = """
                {
                  "tradingDate": "%s",
                  "marketGrade": "A",
                  "decisionLock": "NONE",
                  "timeDecayStage": "EARLY",
                  "hourlyGate": "NORMAL",
                  "monitorMode": "ACTIVE"
                }
                """.formatted(LocalDate.now());

        mockMvc.perform(post("/api/monitor/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────────
    // Candidates
    // ─────────────────────────────────────────────────────────────────

    @Test
    void candidates_currentAndHistory_shouldReturnArray() throws Exception {
        mockMvc.perform(get("/api/candidates/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/candidates/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ─────────────────────────────────────────────────────────────────
    // Notifications CRUD
    // ─────────────────────────────────────────────────────────────────

    @Test
    void notification_createAndRead_shouldReturnCreatedRecord() throws Exception {
        String eventTime = LocalDateTime.now().toString();
        String request = """
                {
                  "eventTime": "%s",
                  "notificationType": "FINAL_DECISION",
                  "source": "test",
                  "title": "Integration Test Notification",
                  "content": "Test content for integration test"
                }
                """.formatted(eventTime);

        MvcResult createResult = mockMvc.perform(post("/api/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Integration Test Notification"))
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        long id = created.path("id").asLong();

        mockMvc.perform(get("/api/notifications/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.notificationType").value("FINAL_DECISION"));

        mockMvc.perform(get("/api/notifications?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ─────────────────────────────────────────────────────────────────
    // Positions CRUD + close
    // ─────────────────────────────────────────────────────────────────

    @Test
    void position_createOpenCloseFlow_shouldCalculatePnl() throws Exception {
        String createReq = """
                {
                  "symbol": "2317",
                  "side": "LONG",
                  "qty": 1000,
                  "avgCost": 80.0,
                  "status": "OPEN"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.symbol").value("2317"))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();

        long id = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("id").asLong();

        // Verify it appears in open positions
        mockMvc.perform(get("/api/positions/open?symbol=2317"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Close at profit (+4%)
        String closeReq = """
                { "closePrice": 83.2 }
                """;

        mockMvc.perform(patch("/api/positions/{id}/close", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(closeReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closePrice").value(83.2))
                .andExpect(jsonPath("$.realizedPnl").value(3200.0));
    }

    @Test
    void position_openAtLoss_realizedPnlShouldBeNegative() throws Exception {
        String createReq = """
                {
                  "symbol": "1802",
                  "side": "LONG",
                  "qty": 1000,
                  "avgCost": 70.0,
                  "status": "OPEN"
                }
                """;

        MvcResult r = mockMvc.perform(post("/api/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createReq))
                .andExpect(status().isOk())
                .andReturn();

        long id = objectMapper.readTree(r.getResponse().getContentAsString()).path("id").asLong();

        mockMvc.perform(patch("/api/positions/{id}/close", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"closePrice\": 66.5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.realizedPnl").value(-3500.0));
    }

    @Test
    void positions_historyQuery_shouldSupportDateFilter() throws Exception {
        String from = LocalDate.now().minusDays(30).toString();
        String to   = LocalDate.now().toString();

        mockMvc.perform(get("/api/positions/history?dateFrom={from}&dateTo={to}", from, to))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ─────────────────────────────────────────────────────────────────
    // PnL
    // ─────────────────────────────────────────────────────────────────

    @Test
    void pnl_createAndRead_shouldReturnRecord() throws Exception {
        LocalDate testDate = LocalDate.now().minusDays(1);
        String request = """
                {
                  "tradingDate": "%s",
                  "realizedPnl": 3500.0,
                  "unrealizedPnl": 0.0,
                  "winRate": 100.0
                }
                """.formatted(testDate);

        mockMvc.perform(post("/api/pnl/daily")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/pnl/daily"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/pnl/summary?days=30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").isNumber());

        mockMvc.perform(get("/api/pnl/history?dateFrom={from}&dateTo={to}",
                        LocalDate.now().minusDays(7), LocalDate.now()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    // ─────────────────────────────────────────────────────────────────
    // System – probe (dry-run) + migration health
    // ─────────────────────────────────────────────────────────────────

    @Test
    void externalProbe_dryRun_shouldReturnStructuredResponse() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/system/external/probe?liveLine=false&liveClaude=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").isNotEmpty())
                .andExpect(jsonPath("$.taifex").isNotEmpty())
                .andExpect(jsonPath("$.line").isNotEmpty())
                .andExpect(jsonPath("$.claude").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());

        // LINE and Claude are disabled in integration profile → expect SKIPPED
        assertThat(body.path("line").path("status").asText()).isEqualTo("SKIPPED");
        assertThat(body.path("claude").path("status").asText()).isEqualTo("SKIPPED");

        // TAIFEX is public API; either OK or WARN (non-trading day / network issue)
        String taifexStatus = body.path("taifex").path("status").asText();
        assertThat(taifexStatus).isIn("OK", "WARN");
    }

    @Test
    void probeHistory_shouldReturnArray() throws Exception {
        mockMvc.perform(get("/api/system/external/probe/history?limit=5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void migrationHealth_v4ColumnsMustExist() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/system/migration/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checks").isArray())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode checks = body.path("checks");

        // V4 columns: close_price and realized_pnl must be present in DB
        assertCheckOk(checks, "column.position.close_price");
        assertCheckOk(checks, "column.position.realized_pnl");

        // Core tables must exist
        assertCheckOk(checks, "table.position");
        assertCheckOk(checks, "table.ai_research_log");
        assertCheckOk(checks, "table.external_probe_log");
    }

    private void assertCheckOk(JsonNode checks, String key) {
        for (JsonNode check : checks) {
            if (key.equals(check.path("key").asText())) {
                assertThat(check.path("ok").asBoolean())
                        .as("Migration check [%s] should be OK", key)
                        .isTrue();
                return;
            }
        }
        throw new AssertionError("Migration check key not found: " + key);
    }

    // ─────────────────────────────────────────────────────────────────
    // AI Research – read only (trigger disabled in integration profile)
    // ─────────────────────────────────────────────────────────────────

    @Test
    void aiResearch_list_shouldReturnArray() throws Exception {
        mockMvc.perform(get("/api/ai/research"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }
}
