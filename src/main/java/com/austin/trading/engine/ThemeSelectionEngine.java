package com.austin.trading.engine;

import com.austin.trading.entity.StockThemeMappingEntity;
import com.austin.trading.entity.ThemeSnapshotEntity;
import com.austin.trading.repository.StockThemeMappingRepository;
import com.austin.trading.repository.ThemeSnapshotRepository;
import com.austin.trading.service.ScoreConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 題材選擇引擎。
 *
 * <h3>評分架構</h3>
 * <pre>
 * market_behavior_score（Java 量化，0-10）
 *   ├─ avgGainScore     (0-5)：題材成員平均漲幅
 *   ├─ strongRatioScore (0-3)：漲幅 >= threshold 的股票比例
 *   └─ consistencyBonus (0-2)：所有成員皆正；所有成員漲幅 >= 1%
 *
 * final_theme_score = market_behavior × w1 + theme_heat × w2 + continuation × w3
 *   w1 = theme.weight.market_behavior  (預設 0.50)
 *   w2 = theme.weight.heat             (預設 0.30)
 *   w3 = theme.weight.continuation     (預設 0.20)
 * </pre>
 */
@Component
public class ThemeSelectionEngine {

    private static final Logger log = LoggerFactory.getLogger(ThemeSelectionEngine.class);

    private final ThemeSnapshotRepository     themeSnapshotRepository;
    private final StockThemeMappingRepository stockThemeMappingRepository;
    private final ScoreConfigService          config;

    public ThemeSelectionEngine(
            ThemeSnapshotRepository themeSnapshotRepository,
            StockThemeMappingRepository stockThemeMappingRepository,
            ScoreConfigService config
    ) {
        this.themeSnapshotRepository    = themeSnapshotRepository;
        this.stockThemeMappingRepository = stockThemeMappingRepository;
        this.config                     = config;
    }

    // ── 公開 record：單檔報價輸入 ─────────────────────────────────────────────

    /**
     * 題材行為分計算所需的單檔報價資料（可由 LiveQuoteResponse 轉換）。
     */
    public record StockQuoteInput(
            String symbol,
            Double changePercent,
            Long   volume,
            Double currentPrice
    ) {}

    // ── 主要公開方法 ──────────────────────────────────────────────────────────

    /**
     * 取得今日排名前 N 的題材快照（依 final_theme_score 降序）。
     */
    public List<ThemeSnapshotEntity> getRankedThemes(LocalDate tradingDate) {
        return themeSnapshotRepository.findByTradingDateOrderByFinalThemeScoreDesc(tradingDate);
    }

    /**
     * 查詢指定股票今日最強題材標籤（final_theme_score 最高的那個）。
     *
     * @return Optional.empty() 若該股無題材對應
     */
    public Optional<String> getLeadingThemeForStock(String symbol, LocalDate tradingDate) {
        List<StockThemeMappingEntity> mappings = stockThemeMappingRepository
                .findBySymbolAndIsActiveTrue(symbol);
        if (mappings.isEmpty()) return Optional.empty();

        List<ThemeSnapshotEntity> snapshots = themeSnapshotRepository
                .findByTradingDateOrderByFinalThemeScoreDesc(tradingDate);

        for (ThemeSnapshotEntity snap : snapshots) {
            for (StockThemeMappingEntity mapping : mappings) {
                if (mapping.getThemeTag().equals(snap.getThemeTag())) {
                    return Optional.of(mapping.getThemeTag());
                }
            }
        }

        return Optional.of(mappings.get(0).getThemeTag());
    }

    /**
     * 計算單檔股票的「題材加乘分」（0.8~1.2 係數）。
     * 有題材快照且 final_theme_score > 0 時，按比例給加乘；否則 1.0。
     */
    public BigDecimal getThemeMultiplier(String symbol, LocalDate tradingDate) {
        Optional<String> leadingTheme = getLeadingThemeForStock(symbol, tradingDate);
        if (leadingTheme.isEmpty()) return BigDecimal.ONE;

        return themeSnapshotRepository
                .findByTradingDateAndThemeTag(tradingDate, leadingTheme.get())
                .map(snap -> {
                    if (snap.getFinalThemeScore() == null) return BigDecimal.ONE;
                    // 將 0~10 分的題材分轉換為 0.8~1.2 的係數
                    BigDecimal normalized = snap.getFinalThemeScore()
                            .divide(new BigDecimal("10"), 4, RoundingMode.HALF_UP);
                    return new BigDecimal("0.8").add(normalized.multiply(new BigDecimal("0.4")));
                })
                .orElse(BigDecimal.ONE);
    }

    /**
     * 計算單一題材的市場行為分（0-10），純計算不寫 DB。
     *
     * @param quotes 屬於該題材的成員股票即時報價
     * @return market_behavior_score（若 quotes 為空則回傳 0）
     */
    public BigDecimal computeMarketBehaviorScore(List<StockQuoteInput> quotes) {
        List<StockQuoteInput> valid = quotes.stream()
                .filter(q -> q.changePercent() != null)
                .toList();
        if (valid.isEmpty()) return BigDecimal.ZERO;

        double threshold = config.getDecimal(
                "theme.strong_stock_threshold_pct", new BigDecimal("2.0")).doubleValue();

        double avgGain = valid.stream()
                .mapToDouble(StockQuoteInput::changePercent).average().orElse(0.0);
        long strongCount = valid.stream()
                .filter(q -> q.changePercent() >= threshold).count();
        double strongRatio = (double) strongCount / valid.size();
        boolean allPositive = valid.stream().allMatch(q -> q.changePercent() >= 0);
        boolean allAbove1   = valid.stream().allMatch(q -> q.changePercent() >= 1.0);

        // avgGainScore (0-5)
        double avgGainScore;
        if      (avgGain >= 5.0) avgGainScore = 5;
        else if (avgGain >= 3.0) avgGainScore = 4;
        else if (avgGain >= 2.0) avgGainScore = 3;
        else if (avgGain >= 1.0) avgGainScore = 2;
        else if (avgGain >= 0.0) avgGainScore = 1;
        else                     avgGainScore = 0;

        // strongRatioScore (0-3)
        double strongRatioScore;
        if      (strongRatio >= 0.7) strongRatioScore = 3;
        else if (strongRatio >= 0.5) strongRatioScore = 2;
        else if (strongRatio >= 0.3) strongRatioScore = 1;
        else                         strongRatioScore = 0;

        // consistencyBonus (0-2)
        double bonus = 0;
        if (allPositive) bonus += 1;
        if (allAbove1)   bonus += 1;

        double raw = avgGainScore + strongRatioScore + bonus;
        double clamped = Math.min(raw, 10.0);
        return BigDecimal.valueOf(clamped).setScale(3, RoundingMode.HALF_UP);
    }

    /**
     * 依一批即時報價，為所有已知題材計算並寫入 market_behavior_score / final_theme_score，
     * 同時更新 rankingOrder。
     *
     * <p>盤中或盤後呼叫；每次呼叫為 upsert（同日同題材會覆蓋）。</p>
     *
     * @param tradingDate 交易日
     * @param allQuotes   全市場（或候選股範圍）即時報價
     * @return 更新後的快照列表（依最終分降序）
     */
    public List<ThemeSnapshotEntity> computeAndSaveAllThemes(
            LocalDate tradingDate,
            List<StockQuoteInput> allQuotes
    ) {
        // 建立 symbol → quote 快速查詢表
        Map<String, StockQuoteInput> quoteMap = allQuotes.stream()
                .collect(Collectors.toMap(StockQuoteInput::symbol, q -> q, (a, b) -> a));

        // 取得所有啟用的 mapping，按 themeTag 分組
        Map<String, List<StockThemeMappingEntity>> mappingsByTheme =
                stockThemeMappingRepository.findAllByOrderBySymbolAscThemeTagAsc()
                        .stream()
                        .filter(m -> Boolean.TRUE.equals(m.getIsActive()))
                        .collect(Collectors.groupingBy(StockThemeMappingEntity::getThemeTag));

        List<ThemeSnapshotEntity> results = new ArrayList<>();

        for (Map.Entry<String, List<StockThemeMappingEntity>> entry : mappingsByTheme.entrySet()) {
            String themeTag = entry.getKey();
            List<StockThemeMappingEntity> members = entry.getValue();

            // 找此題材中有報價的成員股
            List<StockQuoteInput> memberQuotes = members.stream()
                    .map(m -> quoteMap.get(m.getSymbol()))
                    .filter(Objects::nonNull)
                    .toList();

            if (memberQuotes.isEmpty()) {
                log.debug("[ThemeEngine] {} 無可用報價，跳過", themeTag);
                continue;
            }

            BigDecimal mbScore = computeMarketBehaviorScore(memberQuotes);

            // upsert
            ThemeSnapshotEntity snap = themeSnapshotRepository
                    .findByTradingDateAndThemeTag(tradingDate, themeTag)
                    .orElseGet(() -> {
                        ThemeSnapshotEntity e = new ThemeSnapshotEntity();
                        e.setTradingDate(tradingDate);
                        e.setThemeTag(themeTag);
                        members.stream().map(StockThemeMappingEntity::getThemeCategory)
                                .filter(Objects::nonNull).findFirst()
                                .ifPresent(e::setThemeCategory);
                        return e;
                    });

            snap.setMarketBehaviorScore(mbScore);
            snap.setAvgGainPct(computeAvgGainPct(memberQuotes));
            snap.setStrongStockCount((int) memberQuotes.stream()
                    .filter(q -> q.changePercent() != null && q.changePercent() >= config
                            .getDecimal("theme.strong_stock_threshold_pct", new BigDecimal("2.0")).doubleValue())
                    .count());
            snap.setTotalTurnover(computeTotalTurnover(memberQuotes));
            findLeadingStock(memberQuotes).ifPresent(snap::setLeadingStockSymbol);
            snap.setFinalThemeScore(computeFinalThemeScore(
                    mbScore,
                    snap.getThemeHeatScore(),
                    snap.getThemeContinuationScore()
            ));

            results.add(themeSnapshotRepository.save(snap));
        }

        // 重算 rankingOrder
        results.sort(Comparator.comparing(
                snap -> snap.getFinalThemeScore() == null ? BigDecimal.ZERO : snap.getFinalThemeScore(),
                Comparator.reverseOrder()
        ));
        for (int i = 0; i < results.size(); i++) {
            results.get(i).setRankingOrder(i + 1);
            themeSnapshotRepository.save(results.get(i));
        }

        log.info("[ThemeEngine] 計算完成，共更新 {} 個題材快照", results.size());
        return results;
    }

    /**
     * 將 Claude 回傳的 heat / continuation 分數回填至 theme_snapshot，
     * 並自動重算 final_theme_score。
     *
     * @param tradingDate         交易日
     * @param themeTag            題材標籤
     * @param themeHeatScore      Claude 題材熱度分（0-10），可為 null
     * @param continuationScore   Claude 題材延續分（0-10），可為 null
     * @param driverType          驅動類型（法說/政策/報價/事件/籌碼），可為 null
     * @param riskSummary         風險摘要，可為 null
     */
    public ThemeSnapshotEntity mergeClaudeThemeScores(
            LocalDate tradingDate,
            String themeTag,
            BigDecimal themeHeatScore,
            BigDecimal continuationScore,
            String driverType,
            String riskSummary
    ) {
        ThemeSnapshotEntity snap = themeSnapshotRepository
                .findByTradingDateAndThemeTag(tradingDate, themeTag)
                .orElseGet(() -> {
                    ThemeSnapshotEntity e = new ThemeSnapshotEntity();
                    e.setTradingDate(tradingDate);
                    e.setThemeTag(themeTag);
                    return e;
                });

        if (themeHeatScore    != null) snap.setThemeHeatScore(themeHeatScore);
        if (continuationScore != null) snap.setThemeContinuationScore(continuationScore);
        if (driverType        != null) snap.setDriverType(driverType);
        if (riskSummary       != null) snap.setRiskSummary(riskSummary);

        snap.setFinalThemeScore(computeFinalThemeScore(
                snap.getMarketBehaviorScore(),
                snap.getThemeHeatScore(),
                snap.getThemeContinuationScore()
        ));

        ThemeSnapshotEntity saved = themeSnapshotRepository.save(snap);
        log.info("[ThemeEngine] Claude分回填 themeTag={} heat={} continuation={} final={}",
                themeTag, themeHeatScore, continuationScore, saved.getFinalThemeScore());
        return saved;
    }

    // ── 私有工具方法 ───────────────────────────────────────────────────────────

    /**
     * 合併三個子分數為 final_theme_score（0-10）。
     * 若某子分缺失，依剩餘非 null 的分數按比例計算，保持總和為 0-10。
     */
    BigDecimal computeFinalThemeScore(
            BigDecimal marketBehaviorScore,
            BigDecimal themeHeatScore,
            BigDecimal themeContinuationScore
    ) {
        double wMb   = config.getDecimal("theme.weight.market_behavior", new BigDecimal("0.50")).doubleValue();
        double wHeat = config.getDecimal("theme.weight.heat",            new BigDecimal("0.30")).doubleValue();
        double wCont = config.getDecimal("theme.weight.continuation",    new BigDecimal("0.20")).doubleValue();

        double totalWeight = 0;
        double weightedSum = 0;

        if (marketBehaviorScore   != null) { weightedSum += marketBehaviorScore.doubleValue()   * wMb;   totalWeight += wMb;   }
        if (themeHeatScore        != null) { weightedSum += themeHeatScore.doubleValue()        * wHeat; totalWeight += wHeat; }
        if (themeContinuationScore!= null) { weightedSum += themeContinuationScore.doubleValue()* wCont; totalWeight += wCont; }

        if (totalWeight == 0) return BigDecimal.ZERO;

        // 將加權平均正規化回 0-10（避免只有 marketBehaviorScore 時結果偏低）
        double normalized = weightedSum / totalWeight;
        double clamped = Math.min(Math.max(normalized, 0), 10);
        return BigDecimal.valueOf(clamped).setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal computeAvgGainPct(List<StockQuoteInput> quotes) {
        OptionalDouble avg = quotes.stream()
                .filter(q -> q.changePercent() != null)
                .mapToDouble(StockQuoteInput::changePercent)
                .average();
        return avg.isPresent()
                ? BigDecimal.valueOf(avg.getAsDouble()).setScale(4, RoundingMode.HALF_UP)
                : null;
    }

    private BigDecimal computeTotalTurnover(List<StockQuoteInput> quotes) {
        double total = quotes.stream()
                .filter(q -> q.volume() != null && q.currentPrice() != null)
                .mapToDouble(q -> q.volume() * q.currentPrice() * 1000.0)
                .sum();
        return total > 0
                ? BigDecimal.valueOf(total).setScale(2, RoundingMode.HALF_UP)
                : null;
    }

    private Optional<String> findLeadingStock(List<StockQuoteInput> quotes) {
        return quotes.stream()
                .filter(q -> q.changePercent() != null)
                .max(Comparator.comparingDouble(StockQuoteInput::changePercent))
                .map(StockQuoteInput::symbol);
    }
}
