package com.austin.trading.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * P0.5 — TWSE 歷史日線（月份）客戶端。
 *
 * <p>用於補 {@link com.austin.trading.service.regime.RealDowngradeEvaluator}
 * 缺的 60 日 TAIEX / 個股收盤資料。回傳一整月（最多 ~22 個交易日）的 OHLC + 量。</p>
 *
 * <p>API 規格：</p>
 * <ul>
 *   <li>個股: {@code /exchangeReport/STOCK_DAY?response=json&date=YYYYMM01&stockNo=2330}</li>
 *   <li>TAIEX:
 *       {@code /exchangeReport/MI_5MINS_HIST?response=json&date=YYYYMM01}
 *       — 回傳當月每個交易日的開高低收（加權指數）。</li>
 * </ul>
 *
 * <p>失敗（網路、stat != OK、JSON parse 例外）一律回 {@link Optional#empty()}，
 * caller 端必須以「fail-safe = 不更新 DB」處理；不可丟例外導致整個 job 中止。</p>
 */
@Component
public class TwseHistoryClient {

    private static final Logger log = LoggerFactory.getLogger(TwseHistoryClient.class);

    private static final String BASE_URL    = "https://www.twse.com.tw";
    private static final String STOCK_PATH  = "/exchangeReport/STOCK_DAY";
    private static final String TAIEX_PATH  = "/exchangeReport/MI_5MINS_HIST";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WebClient    webClient;
    private final ObjectMapper objectMapper;

    public TwseHistoryClient(WebClient.Builder builder, ObjectMapper objectMapper) {
        this.webClient    = builder.baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
    }

    /**
     * 抓指定月份的個股日線。
     *
     * @param stockNo  TWSE 代號（例如 "2330"）
     * @param yearMonth 月份（任一日皆可，會自動取 1 號當 date 參數）
     */
    public List<DailyBar> fetchStockMonth(String stockNo, YearMonth yearMonth) {
        if (stockNo == null || stockNo.isBlank()) return List.of();
        String dateStr = yearMonth.atDay(1).format(DATE_FMT);
        try {
            String json = webClient.get()
                    .uri(b -> b.path(STOCK_PATH)
                            .queryParam("response", "json")
                            .queryParam("date",     dateStr)
                            .queryParam("stockNo",  stockNo)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            if (json == null || json.isBlank()) return List.of();

            JsonNode root = objectMapper.readTree(json);
            String stat = root.path("stat").asText("");
            if (!"OK".equalsIgnoreCase(stat)) {
                log.debug("[TwseHistory] STOCK_DAY {} {} stat={}", stockNo, dateStr, stat);
                return List.of();
            }

            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return List.of();

            List<DailyBar> bars = new ArrayList<>(data.size());
            for (JsonNode row : data) {
                if (!row.isArray() || row.size() < 7) continue;
                // STOCK_DAY 欄位順序: 日期(民國)/成交股數/成交金額/開/高/低/收/漲跌價差/筆數
                LocalDate d = parseRocDate(row.get(0).asText());
                if (d == null) continue;
                Long volume    = parseLong(row.get(1).asText());
                BigDecimal o   = parseDecimal(row.get(3).asText());
                BigDecimal h   = parseDecimal(row.get(4).asText());
                BigDecimal l   = parseDecimal(row.get(5).asText());
                BigDecimal c   = parseDecimal(row.get(6).asText());
                if (c == null) continue; // close 必填，沒有就跳過
                bars.add(new DailyBar(stockNo, d, o, h, l, c, volume));
            }
            return bars;
        } catch (Exception e) {
            log.warn("[TwseHistory] STOCK_DAY {} {} failed: {}", stockNo, dateStr, e.getMessage());
            return List.of();
        }
    }

    /**
     * 抓指定月份的 TAIEX 加權指數歷史。回傳 symbol 統一寫作 "t00"。
     *
     * <p>{@code MI_5MINS_HIST} 回傳的 {@code data} 每列為
     * {日期、開、高、低、收} 五欄；無成交量。</p>
     */
    public List<DailyBar> fetchTaiexMonth(YearMonth yearMonth) {
        String dateStr = yearMonth.atDay(1).format(DATE_FMT);
        try {
            String json = webClient.get()
                    .uri(b -> b.path(TAIEX_PATH)
                            .queryParam("response", "json")
                            .queryParam("date",     dateStr)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            if (json == null || json.isBlank()) return List.of();

            JsonNode root = objectMapper.readTree(json);
            String stat = root.path("stat").asText("");
            if (!"OK".equalsIgnoreCase(stat)) {
                log.debug("[TwseHistory] MI_5MINS_HIST {} stat={}", dateStr, stat);
                return List.of();
            }

            JsonNode data = root.get("data");
            if (data == null || !data.isArray()) return List.of();

            List<DailyBar> bars = new ArrayList<>(data.size());
            for (JsonNode row : data) {
                if (!row.isArray() || row.size() < 5) continue;
                LocalDate d   = parseRocDate(row.get(0).asText());
                if (d == null) continue;
                BigDecimal o  = parseDecimal(row.get(1).asText());
                BigDecimal h  = parseDecimal(row.get(2).asText());
                BigDecimal l  = parseDecimal(row.get(3).asText());
                BigDecimal c  = parseDecimal(row.get(4).asText());
                if (c == null) continue;
                bars.add(new DailyBar("t00", d, o, h, l, c, null));
            }
            return bars;
        } catch (Exception e) {
            log.warn("[TwseHistory] MI_5MINS_HIST {} failed: {}", dateStr, e.getMessage());
            return List.of();
        }
    }

    // ── 日期解析：TWSE 用民國年，如 "114/04/28" ──────────────────────────────

    private static LocalDate parseRocDate(String s) {
        if (s == null || s.isBlank()) return null;
        String trimmed = s.trim().replace(" ", "");
        String[] parts = trimmed.split("/");
        if (parts.length != 3) return null;
        try {
            int rocYear  = Integer.parseInt(parts[0]);
            int month    = Integer.parseInt(parts[1]);
            int day      = Integer.parseInt(parts[2]);
            return LocalDate.of(rocYear + 1911, month, day);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal parseDecimal(String s) {
        if (s == null) return null;
        String t = s.trim().replace(",", "");
        if (t.isEmpty() || "-".equals(t) || "--".equals(t) || "X".equalsIgnoreCase(t)) return null;
        try { return new BigDecimal(t); } catch (NumberFormatException e) { return null; }
    }

    private static Long parseLong(String s) {
        if (s == null) return null;
        String t = s.trim().replace(",", "");
        if (t.isEmpty() || "-".equals(t) || "--".equals(t)) return null;
        try { return Long.parseLong(t); } catch (NumberFormatException e) { return null; }
    }

    /** 一筆 OHLC 條 — POJO record，內部 DTO，不對外（只給 Job/Provider 用）。 */
    public record DailyBar(
            String symbol,
            LocalDate tradingDate,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            Long volume
    ) {}
}
