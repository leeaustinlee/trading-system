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
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * TAIFEX 期貨報價客戶端。
 * <p>
 * 使用 TAIFEX Open API 取得台指期近月合約日盤資料。
 * API URL 可透過 {@code trading.taifex.base-url} 設定。
 * </p>
 */
@Component
public class TaifexClient {

    private static final Logger log = LoggerFactory.getLogger(TaifexClient.class);

    private static final String DEFAULT_BASE = "https://openapi.taifex.com.tw";
    private static final String DAILY_PATH   = "/v1/DailyFutures";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

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
     * @param date 交易日；輸入 null 時自動使用今日
     * @return 報價；API 不可用時返回 empty
     */
    public Optional<FuturesQuote> getTxfQuote(LocalDate date) {
        LocalDate queryDate = date == null ? LocalDate.now() : date;
        String dateStr = queryDate.format(DATE_FMT);

        try {
            String json = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(DAILY_PATH)
                            .queryParam("date", dateStr)
                            .queryParam("contractCode", "TX")
                            .queryParam("queryType", "1")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (json == null || json.isBlank()) return Optional.empty();

            JsonNode root = objectMapper.readTree(json);

            // TAIFEX Open API 返回陣列，取第一筆近月合約
            if (root.isArray() && root.size() > 0) {
                JsonNode first = root.get(0);
                return Optional.ofNullable(parseQuote(first));
            }

        } catch (Exception e) {
            log.warn("[TaifexClient] TX {} failed: {}", dateStr, e.getMessage());
        }

        return Optional.empty();
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────────

    private FuturesQuote parseQuote(JsonNode node) {
        // TAIFEX 欄位在不同資料集命名可能略有差異，這裡做多組 fallback。
        Double close     = priceAny(node, "Close", "ClosePrice", "LastPrice");
        Double prevClose = priceAny(node, "PrevClose", "PreviousClose", "SettlementPrice", "PreSettlementPrice");
        Double change    = priceAny(node, "Change", "PriceChange");
        Long volume      = longAny(node, "TotalVolume", "Volume", "TradingVolume");

        if (close == null) return null;

        Double changePct = null;
        if (change == null && prevClose != null) {
            change = close - prevClose;
        }
        if (prevClose != null && prevClose != 0 && change != null) {
            changePct = Math.round(change / prevClose * 10000.0) / 100.0;
        }

        String time = strAny(node, "Time", "UpdateTime");
        return new FuturesQuote("TX", close, prevClose, change, changePct, volume, time, true);
    }

    private String strAny(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = str(node, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private Double priceAny(JsonNode node, String... fields) {
        for (String field : fields) {
            Double value = price(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private Long longAny(JsonNode node, String... fields) {
        for (String field : fields) {
            Long value = longVal(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String str(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return null;
        return n.asText().trim();
    }

    private Double price(JsonNode node, String field) {
        String s = str(node, field);
        if (s == null || "-".equals(s) || s.isEmpty()) return null;
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long longVal(JsonNode node, String field) {
        String s = str(node, field);
        if (s == null || "-".equals(s) || s.isEmpty()) return null;
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
