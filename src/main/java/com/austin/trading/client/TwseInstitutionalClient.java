package com.austin.trading.client;

import com.austin.trading.client.dto.InstitutionalFlow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * TWSE T86 三大法人買賣超 API 客戶端。
 * <p>
 * 每日盤後（約 16:30 之後）才有當日資料。
 * </p>
 */
@Component
public class TwseInstitutionalClient {

    private static final Logger log = LoggerFactory.getLogger(TwseInstitutionalClient.class);

    private static final String BASE_URL = "https://www.twse.com.tw";
    private static final String T86_PATH = "/fund/T86";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    // T86 欄位索引（0-based）
    // 0:代號, 1:名稱, 2:外陸資買進, 3:外陸資賣出, 4:外陸資淨買,
    // 5:外資自營商買進, 6:外資自營商賣出, 7:外資自營商淨買,
    // 8:投信買進, 9:投信賣出, 10:投信淨買,
    // 11:自營商自行買進, 12:自營商自行賣出, 13:自營商自行淨買,
    // 14:自營商避險買進, 15:自營商避險賣出, 16:自營商避險淨買,
    // 17:自營商淨買, 18:三大法人合計
    private static final int IDX_SYMBOL       = 0;
    private static final int IDX_NAME         = 1;
    private static final int IDX_FOREIGN_NET  = 4;
    private static final int IDX_TRUST_NET    = 10;
    private static final int IDX_DEALER_NET   = 17;
    private static final int IDX_TOTAL_NET    = 18;
    private static final int MIN_COLUMNS      = 19;

    private final WebClient   webClient;
    private final ObjectMapper objectMapper;

    public TwseInstitutionalClient(WebClient.Builder builder, ObjectMapper objectMapper) {
        this.webClient    = builder.baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
    }

    /**
     * 查詢指定交易日的 T86 三大法人資料。
     *
     * @param date 交易日
     * @return 所有上市股票的法人流向，API 失敗時返回空 List
     */
    public List<InstitutionalFlow> getT86(LocalDate date) {
        String dateStr = date.format(DATE_FMT);
        try {
            String json = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(T86_PATH)
                            .queryParam("response", "json")
                            .queryParam("date", dateStr)
                            .queryParam("selectType", "ALL")
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();

            if (json == null || json.isBlank()) return List.of();

            JsonNode root = objectMapper.readTree(json);

            // TWSE API 成功狀態為 "OK"
            String stat = root.path("stat").asText();
            if (!"OK".equalsIgnoreCase(stat)) {
                log.warn("[TwseInstitutionalClient] T86 stat={} date={}", stat, dateStr);
                return List.of();
            }

            JsonNode dataNode = root.get("data");
            if (dataNode == null || !dataNode.isArray()) return List.of();

            List<InstitutionalFlow> results = new ArrayList<>();
            for (JsonNode row : dataNode) {
                if (!row.isArray() || row.size() < MIN_COLUMNS) continue;
                InstitutionalFlow flow = parseRow(row);
                if (flow != null) results.add(flow);
            }

            log.info("[TwseInstitutionalClient] T86 {} rows={}", dateStr, results.size());
            return results;

        } catch (Exception e) {
            log.warn("[TwseInstitutionalClient] T86 {} failed: {}", dateStr, e.getMessage());
            return List.of();
        }
    }

    /** 查詢單一標的的法人資料（從全量資料中篩選） */
    public Optional<InstitutionalFlow> getT86ForSymbol(LocalDate date, String symbol) {
        return getT86(date).stream()
                .filter(f -> symbol.equals(f.symbol()))
                .findFirst();
    }

    // ── 私有方法 ─────────────────────────────────────────────────────────────────

    private InstitutionalFlow parseRow(JsonNode row) {
        String symbol = cell(row, IDX_SYMBOL);
        String name   = cell(row, IDX_NAME);
        if (symbol == null || symbol.isEmpty()) return null;

        Long foreignNet    = parseLong(cell(row, IDX_FOREIGN_NET));
        Long investTrustNet = parseLong(cell(row, IDX_TRUST_NET));
        Long dealerNet     = parseLong(cell(row, IDX_DEALER_NET));
        Long totalNet      = parseLong(cell(row, IDX_TOTAL_NET));

        return new InstitutionalFlow(symbol, name, foreignNet, investTrustNet, dealerNet, totalNet);
    }

    private String cell(JsonNode row, int index) {
        JsonNode n = row.get(index);
        if (n == null || n.isNull()) return null;
        return n.asText().trim();
    }

    private Long parseLong(String s) {
        if (s == null || s.isBlank() || "-".equals(s)) return null;
        try {
            return Long.parseLong(s.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
