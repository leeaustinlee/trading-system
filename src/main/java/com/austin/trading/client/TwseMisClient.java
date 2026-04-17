package com.austin.trading.client;

import com.austin.trading.client.dto.StockQuote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * TWSE MIS 即時報價 API 客戶端。
 * <p>
 * 上市（TSE）：ex_ch 前綴 {@code tse_}，例如 {@code tse_2330.tw}
 * 上櫃（OTC）：ex_ch 前綴 {@code otc_}，例如 {@code otc_6669.tw}
 * </p>
 * 盤中（09:00-13:30）才有 currentPrice；盤外回傳昨收。
 */
@Component
public class TwseMisClient {

    private static final Logger log = LoggerFactory.getLogger(TwseMisClient.class);

    private static final String BASE_URL    = "https://mis.twse.com.tw";
    private static final String STOCK_PATH  = "/stock/api/getStockInfo.jsp";
    private static final int    BATCH_LIMIT = 20; // 每次最多查詢筆數

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public TwseMisClient(WebClient.Builder builder, ObjectMapper objectMapper) {
        this.webClient    = builder.baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
    }

    // ── 上市 (TSE) ──────────────────────────────────────────────────────────────

    public List<StockQuote> getTseQuotes(List<String> symbols) {
        return getQuotes(symbols, "tse");
    }

    public Optional<StockQuote> getTseQuote(String symbol) {
        return getTseQuotes(List.of(symbol)).stream().findFirst();
    }

    // ── 上櫃 (OTC) ──────────────────────────────────────────────────────────────

    public List<StockQuote> getOtcQuotes(List<String> symbols) {
        return getQuotes(symbols, "otc");
    }

    public Optional<StockQuote> getOtcQuote(String symbol) {
        return getOtcQuotes(List.of(symbol)).stream().findFirst();
    }

    // ── 混合查詢（自動選前綴）───────────────────────────────────────────────────

    /**
     * 自動選 tse/otc 前綴查詢。
     * 預設以 tse 查詢；若需 OTC 請在 symbol 前加 "otc:" 前綴（例如 "otc:6669"）。
     */
    public List<StockQuote> getQuotesAuto(List<String> rawSymbols) {
        List<String> tse = new ArrayList<>();
        List<String> otc = new ArrayList<>();
        for (String raw : rawSymbols) {
            if (raw.startsWith("otc:")) {
                otc.add(raw.substring(4));
            } else {
                tse.add(raw.startsWith("tse:") ? raw.substring(4) : raw);
            }
        }
        List<StockQuote> result = new ArrayList<>();
        if (!tse.isEmpty()) result.addAll(getTseQuotes(tse));
        if (!otc.isEmpty()) result.addAll(getOtcQuotes(otc));
        return result;
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────────

    private List<StockQuote> getQuotes(List<String> symbols, String market) {
        if (symbols == null || symbols.isEmpty()) return List.of();

        // 分批，每批最多 BATCH_LIMIT 筆
        List<StockQuote> allResults = new ArrayList<>();
        for (int i = 0; i < symbols.size(); i += BATCH_LIMIT) {
            List<String> batch = symbols.subList(i, Math.min(i + BATCH_LIMIT, symbols.size()));
            allResults.addAll(fetchBatch(batch, market));
        }
        return allResults;
    }

    private List<StockQuote> fetchBatch(List<String> symbols, String market) {
        String exCh = symbols.stream()
                .map(s -> market + "_" + s + ".tw")
                .collect(Collectors.joining("|"));
        try {
            String json = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(STOCK_PATH)
                            .queryParam("ex_ch", exCh)
                            .queryParam("_", System.currentTimeMillis())
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (json == null || json.isBlank()) return List.of();

            JsonNode root     = objectMapper.readTree(json);
            JsonNode msgArray = root.get("msgArray");
            if (msgArray == null || !msgArray.isArray()) return List.of();

            List<StockQuote> results = new ArrayList<>();
            for (JsonNode node : msgArray) {
                StockQuote q = parseQuote(node, market);
                if (q != null) results.add(q);
            }
            return results;

        } catch (Exception e) {
            log.warn("[TwseMisClient] fetch {} {}: {}", market, symbols, e.getMessage());
            return List.of();
        }
    }

    private StockQuote parseQuote(JsonNode node, String market) {
        String symbol = str(node, "c");
        if (symbol == null) return null;

        Double current  = price(node, "z");
        Double prevClose = price(node, "y");
        Double open     = price(node, "o");
        Double high     = price(node, "h");
        Double low      = price(node, "l");
        Double bid      = price(node, "b");
        Double ask      = price(node, "a");
        Long   volume   = volume(node, "v");
        String date     = str(node, "d");
        String time     = str(node, "t");
        String name     = str(node, "n");

        return new StockQuote(symbol, name, market,
                current, prevClose, open, high, low, bid, ask,
                volume, date, time, current != null);
    }

    private String str(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return null;
        String s = n.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private Double price(JsonNode node, String field) {
        String s = str(node, field);
        if (s == null || "-".equals(s) || "--".equals(s)) return null;
        try {
            return Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long volume(JsonNode node, String field) {
        String s = str(node, field);
        if (s == null || "-".equals(s)) return null;
        try {
            return (long) Double.parseDouble(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
