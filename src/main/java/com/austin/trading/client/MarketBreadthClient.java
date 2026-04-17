package com.austin.trading.client;

import com.austin.trading.client.dto.MarketBreadth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 大盤漲跌家數與加權指數客戶端。
 * <p>
 * 使用 TWSE MI_INDEX API（盤後）取得當日漲跌家數。
 * 盤後資料約 14:30-15:30 之後才可用。
 * </p>
 */
@Component
public class MarketBreadthClient {

    private static final Logger log = LoggerFactory.getLogger(MarketBreadthClient.class);

    private static final String BASE_URL   = "https://www.twse.com.tw";
    private static final String INDEX_PATH = "/exchangeReport/MI_INDEX";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WebClient    webClient;
    private final ObjectMapper objectMapper;

    public MarketBreadthClient(WebClient.Builder builder, ObjectMapper objectMapper) {
        this.webClient    = builder.baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
    }

    /**
     * 取得指定交易日的市場漲跌家數與加權指數。
     *
     * @param date 交易日
     * @return 市場廣度資料；API 失敗或盤中尚未更新時返回 empty
     */
    public Optional<MarketBreadth> getBreadth(LocalDate date) {
        String dateStr = date.format(DATE_FMT);
        try {
            String json = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(INDEX_PATH)
                            .queryParam("response", "json")
                            .queryParam("date", dateStr)
                            .queryParam("type", "ALLBUT0999")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            if (json == null || json.isBlank()) return Optional.empty();

            JsonNode root = objectMapper.readTree(json);
            String stat = root.path("stat").asText();
            if (!"OK".equalsIgnoreCase(stat)) {
                log.debug("[MarketBreadthClient] stat={} date={}", stat, dateStr);
                return Optional.empty();
            }

            // MI_INDEX 回傳多個 table；data4 含漲跌家數，最後一行為合計
            int advances  = parseCell(root, "data4", 1);  // 上漲
            int unchanged = parseCell(root, "data4", 2);  // 持平
            int declines  = parseCell(root, "data4", 3);  // 下跌

            Double indexValue  = parseIndexValue(root);
            Double indexChange = parseIndexChange(root);
            Double changePct   = null;
            if (indexValue != null && indexChange != null) {
                double base = indexValue - indexChange;
                if (base != 0) changePct = Math.round(indexChange / base * 10000.0) / 100.0;
            }

            return Optional.of(new MarketBreadth(
                    advances, declines, unchanged, indexValue, indexChange, changePct, dateStr));

        } catch (Exception e) {
            log.warn("[MarketBreadthClient] {} failed: {}", dateStr, e.getMessage());
            return Optional.empty();
        }
    }

    // ── 私有解析工具 ─────────────────────────────────────────────────────────────

    private int parseCell(JsonNode root, String tableKey, int colIndex) {
        JsonNode table = root.get(tableKey);
        if (table == null || !table.isArray() || table.isEmpty()) return 0;
        JsonNode lastRow = table.get(table.size() - 1);
        if (lastRow == null || !lastRow.isArray() || lastRow.size() <= colIndex) return 0;
        return parseInt(lastRow.get(colIndex).asText());
    }

    private Double parseIndexValue(JsonNode root) {
        JsonNode data = root.get("data2");
        if (data == null || !data.isArray()) return null;
        for (JsonNode row : data) {
            if (!row.isArray() || row.size() < 2) continue;
            String name = row.get(0).asText();
            if (name.contains("加權") || name.contains("TAIEX")) {
                return parseDouble(row.get(1).asText());
            }
        }
        return null;
    }

    private Double parseIndexChange(JsonNode root) {
        JsonNode data = root.get("data2");
        if (data == null || !data.isArray()) return null;
        for (JsonNode row : data) {
            if (!row.isArray() || row.size() < 3) continue;
            String name = row.get(0).asText();
            if (name.contains("加權") || name.contains("TAIEX")) {
                return parseDouble(row.get(2).asText());
            }
        }
        return null;
    }

    private int parseInt(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s.replace(",", "").trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private Double parseDouble(String s) {
        if (s == null || s.isBlank() || "-".equals(s)) return null;
        try { return Double.parseDouble(s.replace(",", "").trim()); }
        catch (NumberFormatException e) { return null; }
    }
}
