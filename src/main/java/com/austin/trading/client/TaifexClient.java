package com.austin.trading.client;

import com.austin.trading.client.dto.FuturesQuote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * TAIFEX 期貨報價客戶端。
 * <p>
 * 使用 TAIFEX Open API {@code /v1/DailyMarketReportFut}（期貨每日交易行情）。
 * 此 API 不接受 date / contractCode 查詢參數，永遠回傳「最新交易日」全部期貨契約；
 * 本 client 負責從中挑出指定契約（預設 TX）近月「一般」時段那一筆。
 * </p>
 */
@Component
public class TaifexClient {

    private static final Logger log = LoggerFactory.getLogger(TaifexClient.class);

    private static final String DEFAULT_BASE = "https://openapi.taifex.com.tw";
    private static final String DAILY_PATH   = "/v1/DailyMarketReportFut";
    private static final String CONTRACT_TX  = "TX";
    private static final String SESSION_REGULAR = "一般";

    private final WebClient    webClient;
    private final ObjectMapper objectMapper;

    public TaifexClient(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${trading.taifex.base-url:" + DEFAULT_BASE + "}") String baseUrl
    ) {
        this.webClient    = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    /**
     * 取得台指期（TX）近月合約報價。
     *
     * @param date 交易日；此 API 不支援日期查詢，傳入日期會被忽略（僅保留簽名相容性）。
     *             若 date 非 null 且非 API 回傳的日期，會在 log 標示以供追蹤。
     * @return 報價；API 不可用時返回 empty
     */
    public Optional<FuturesQuote> getTxfQuote(LocalDate date) {
        try {
            String json = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path(DAILY_PATH).build())
                    .header("Accept", "application/json")
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (json == null || json.isBlank()) {
                log.warn("[TaifexClient] empty response from {}", DAILY_PATH);
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray() || root.isEmpty()) {
                log.warn("[TaifexClient] response not array or empty");
                return Optional.empty();
            }

            // 篩出指定契約 + 「一般」時段的所有月份，取最接近到期的一筆（ContractMonth(Week) 數值最小）
            List<JsonNode> tx = new ArrayList<>();
            for (JsonNode n : root) {
                String contract = str(n, "Contract");
                String session  = str(n, "TradingSession");
                if (CONTRACT_TX.equals(contract) && SESSION_REGULAR.equals(session)) {
                    tx.add(n);
                }
            }
            if (tx.isEmpty()) {
                log.warn("[TaifexClient] no {} {} contract rows in response ({} rows total)",
                        CONTRACT_TX, SESSION_REGULAR, root.size());
                return Optional.empty();
            }
            tx.sort(Comparator.comparing(n -> defaultStr(str(n, "ContractMonth(Week)"), "999999")));
            JsonNode near = tx.get(0);

            FuturesQuote q = parseQuote(near);
            if (q == null) {
                log.warn("[TaifexClient] parse failed for near contract row");
                return Optional.empty();
            }

            if (date != null) {
                String apiDate = str(near, "Date");
                if (apiDate != null && !apiDate.equals(date.toString().replace("-", ""))) {
                    log.debug("[TaifexClient] requested date={} but API latest is {}", date, apiDate);
                }
            }
            return Optional.of(q);

        } catch (Exception e) {
            log.warn("[TaifexClient] TX query failed: {}", e.getMessage());
        }

        return Optional.empty();
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────────

    private FuturesQuote parseQuote(JsonNode node) {
        Double last       = priceAny(node, "Last", "Close", "ClosePrice", "LastPrice");
        Double settlement = priceAny(node, "SettlementPrice", "PrevClose", "PreviousClose");
        Double change     = priceAny(node, "Change", "PriceChange");
        Double pct        = percent(str(node, "%"));
        Long   volume     = longAny(node, "Volume", "TotalVolume", "TradingVolume");

        if (last == null) return null;

        // prevClose 優先使用 SettlementPrice（若存在），否則 last - change
        Double prevClose = settlement;
        if (prevClose == null && change != null) {
            prevClose = last - change;
        }
        if (change == null && prevClose != null) {
            change = last - prevClose;
        }
        if (pct == null && change != null && prevClose != null && prevClose != 0) {
            pct = Math.round(change / prevClose * 10000.0) / 100.0;
        }

        return new FuturesQuote(CONTRACT_TX, last, prevClose, change, pct, volume, null, true);
    }

    private Double percent(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw)) return null;
        String s = raw.replace("%", "").replace(",", "").trim();
        if (s.isEmpty()) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String defaultStr(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private Double priceAny(JsonNode node, String... fields) {
        for (String field : fields) {
            Double value = price(node, field);
            if (value != null) return value;
        }
        return null;
    }

    private Long longAny(JsonNode node, String... fields) {
        for (String field : fields) {
            Long value = longVal(node, field);
            if (value != null) return value;
        }
        return null;
    }

    private String str(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private Double price(JsonNode node, String field) {
        String s = str(node, field);
        if (s == null || "-".equals(s) || "NULL".equalsIgnoreCase(s)) return null;
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longVal(JsonNode node, String field) {
        String s = str(node, field);
        if (s == null || "-".equals(s) || "NULL".equalsIgnoreCase(s)) return null;
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
