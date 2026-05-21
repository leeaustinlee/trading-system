package com.austin.trading.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.austin.trading.config.FixedLiveTradingClockTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@Import(FixedLiveTradingClockTestConfig.class)
class ApiIntegrationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void finalDecisionEvaluateShouldReturnEnterForQualifiedCandidate() throws Exception {
        String request = """
                {
                  "marketGrade": "A",
                  "decisionLock": "NONE",
                  "timeDecayStage": "EARLY",
                  "hasPosition": false,
                  "candidates": [
                    {
                      "stockCode": "2330",
                      "stockName": "TSMC",
                      "valuationMode": "VALUE_GROWTH",
                      "entryType": "BREAKOUT",
                      "riskRewardRatio": 2.5,
                      "includeInFinalPlan": true,
                      "mainStream": true,
                      "falseBreakout": false,
                      "belowOpen": false,
                      "belowPrevClose": false,
                      "nearDayHigh": false,
                      "stopLossReasonable": true,
                      "rationale": "Trend confirmed",
                      "entryPriceZone": "998.00-1002.00",
                      "stopLossPrice": 980.0,
                      "takeProfit1": 1030.0,
                      "takeProfit2": 1060.0,
                      "finalRankScore": 9.0,
                      "isVetoed": false,
                      "entryTriggered": true
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/decisions/final/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("ENTER"))
                .andExpect(jsonPath("$.selectedStocks[0].stockCode").value("2330"));
    }

    @Test
    void finalDecisionEvaluateIgnoresNarrativeContextAndKeepsHardGateDecision() throws Exception {
        String request = """
                {
                  "marketGrade": "C",
                  "decisionLock": "NONE",
                  "timeDecayStage": "EARLY",
                  "hasPosition": false,
                  "candidates": [
                    {
                      "stockCode": "4989",
                      "stockName": "榮科",
                      "valuationMode": "VALUE_GROWTH",
                      "entryType": "BREAKOUT",
                      "riskRewardRatio": 3.0,
                      "includeInFinalPlan": true,
                      "mainStream": true,
                      "falseBreakout": false,
                      "belowOpen": false,
                      "belowPrevClose": false,
                      "nearDayHigh": false,
                      "stopLossReasonable": true,
                      "rationale": "Narrative context is strong but market grade C must still rest",
                      "entryPriceZone": "30.00-31.00",
                      "stopLossPrice": 28.5,
                      "takeProfit1": 33.0,
                      "takeProfit2": 35.0,
                      "finalRankScore": 9.5,
                      "isVetoed": false,
                      "entryTriggered": true,
                      "narrativeContext": {
                        "weakSignalOnly": true,
                        "theme": "PCB/載板/材料",
                        "attention": 9.5,
                        "shadowBoost": 0.2,
                        "guardrail": "shadow only"
                      }
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/decisions/final/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REST"))
                .andExpect(jsonPath("$.selectedStocks").isEmpty());
    }

    @Test
    void dashboardCurrentShouldReturnResponseEnvelope() throws Exception {
        mockMvc.perform(get("/api/dashboard/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates").isArray());
    }

    @Test
    void closePositionShouldCalculateRealizedPnl() throws Exception {
        // v3：ledger-backed — 建倉前確保現金足夠（cost=1000 + fee<=30）
        mockMvc.perform(post("/api/capital/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":100000,\"note\":\"test seed\"}"))
                .andExpect(status().isOk());

        String createRequest = """
                {
                  "symbol": "2330",
                  "side": "LONG",
                  "qty": 10,
                  "avgCost": 100,
                  "status": "OPEN"
                }
                """;

        MvcResult createResult = mockMvc.perform(post("/api/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andReturn();

        JsonNode createJson = objectMapper.readTree(createResult.getResponse().getContentAsString());
        long id = createJson.path("id").asLong();

        String closeRequest = """
                {
                  "closePrice": 110
                }
                """;

        mockMvc.perform(patch("/api/positions/{id}/close", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(closeRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closePrice").value(110))
                .andExpect(jsonPath("$.realizedPnl").value(100));
    }
}
