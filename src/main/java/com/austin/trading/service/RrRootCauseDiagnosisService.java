package com.austin.trading.service;

import com.austin.trading.dto.response.RiskRewardShadowGateResult;
import com.austin.trading.dto.response.RrRootCauseDiagnosisResponse;
import com.austin.trading.dto.response.RrRootCauseDiagnosisResponse.RootCauseBucket;
import com.austin.trading.dto.response.RrRootCauseDiagnosisResponse.ShadowImpact;
import com.austin.trading.entity.CandidateForwardTrackingEntity;
import com.austin.trading.entity.CandidateStockEntity;
import com.austin.trading.entity.PaperTradeEntity;
import com.austin.trading.repository.CandidateForwardTrackingRepository;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PaperTradeRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RrRootCauseDiagnosisService {

    private static final BigDecimal STOP_TOO_WIDE_PCT = new BigDecimal("6.0");
    private static final BigDecimal TARGET_TOO_CLOSE_PCT = new BigDecimal("3.0");
    private static final BigDecimal ENTRY_NEAR_DAY_HIGH_RATIO = new BigDecimal("0.98");
    private static final BigDecimal MISSED_WINNER_PCT = new BigDecimal("5.0");
    private static final int MIN_IMPACT_SAMPLE = 3;

    private final PaperTradeRepository paperTradeRepository;
    private final CandidateForwardTrackingRepository forwardTrackingRepository;
    private final CandidateStockRepository candidateStockRepository;
    private final RiskRewardShadowGateService shadowGateService;
    private final ObjectMapper objectMapper;

    public RrRootCauseDiagnosisService(PaperTradeRepository paperTradeRepository,
                                       CandidateForwardTrackingRepository forwardTrackingRepository,
                                       CandidateStockRepository candidateStockRepository,
                                       RiskRewardShadowGateService shadowGateService,
                                       ObjectMapper objectMapper) {
        this.paperTradeRepository = paperTradeRepository;
        this.forwardTrackingRepository = forwardTrackingRepository;
        this.candidateStockRepository = candidateStockRepository;
        this.shadowGateService = shadowGateService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RrRootCauseDiagnosisResponse diagnose(int days) {
        int window = days > 0 ? days : 60;
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(window);
        List<PaperTradeEntity> trades = paperTradeRepository.findByEntryDateBetweenOrderByEntryDateAscIdAsc(start, end);
        List<CandidateForwardTrackingEntity> forwardRows = forwardTrackingRepository.findByTradingDateBetween(start, end);
        List<CandidateStockEntity> candidateRows = candidateStockRepository.findByTradingDateBetweenOrderByTradingDateDescScoreDesc(start, end);

        Map<String, CandidateForwardTrackingEntity> forwardByTrade = new LinkedHashMap<>();
        for (CandidateForwardTrackingEntity row : forwardRows) {
            forwardByTrade.putIfAbsent(traceKey(row.getTradingDate(), row.getStockId()), row);
        }
        Map<String, CandidateStockEntity> candidateByTrade = new LinkedHashMap<>();
        for (CandidateStockEntity row : candidateRows) {
            candidateByTrade.putIfAbsent(traceKey(row.getTradingDate(), row.getSymbol()), row);
        }

        List<String> dataGaps = new ArrayList<>();
        Map<Bucket, List<AnalyzedTrade>> buckets = new EnumMap<>(Bucket.class);
        for (Bucket bucket : Bucket.values()) buckets.put(bucket, new ArrayList<>());

        List<AnalyzedTrade> analyzed = new ArrayList<>();
        int lowRrCount = 0;
        for (PaperTradeEntity trade : trades) {
            CandidateForwardTrackingEntity forward = forwardByTrade.get(traceKey(trade.getEntryDate(), trade.getSymbol()));
            CandidateStockEntity candidate = candidateByTrade.get(traceKey(trade.getEntryDate(), trade.getSymbol()));
            AnalyzedTrade item = analyzeTrade(trade, forward, candidate);
            analyzed.add(item);
            if (!RiskRewardShadowGateService.PASS.equals(item.shadowResult().shadowStatus())) {
                lowRrCount++;
                buckets.get(classify(item, dataGaps)).add(item);
            }
        }

        if (trades.isEmpty()) {
            dataGaps.add("DATA_GAP: no paper_trade rows in requested RR diagnosis window");
        }
        long dataGapGateRows = analyzed.stream()
                .filter(a -> RiskRewardShadowGateService.DATA_GAP.equals(a.shadowResult().shadowStatus()))
                .count();
        if (dataGapGateRows > 0) {
            dataGaps.add("DATA_GAP: shadow RR gate missing required price fields rows=" + dataGapGateRows);
        }

        return new RrRootCauseDiagnosisResponse(
                window,
                trades.size(),
                analyzed.size(),
                lowRrCount,
                pct(lowRrCount, trades.size()),
                avg(analyzed.stream().map(a -> a.shadowResult().rrValue()).toList(), 4),
                avg(analyzed.stream().map(AnalyzedTrade::entryToStopPct).toList(), 4),
                avg(analyzed.stream().map(AnalyzedTrade::target1GainPct).toList(), 4),
                avg(analyzed.stream().map(AnalyzedTrade::target2GainPct).toList(), 4),
                toBucketResponses(buckets, trades.size()),
                shadowImpact(analyzed),
                dataGaps
        );
    }

    private AnalyzedTrade analyzeTrade(PaperTradeEntity trade,
                                       CandidateForwardTrackingEntity forward,
                                       CandidateStockEntity candidate) {
        RiskRewardShadowGateResult gate = shadowGateService.evaluate(new RiskRewardShadowGateService.PriceSnapshot(
                trade.getSymbol(),
                trade.getStrategyType(),
                trade.getEntryPrice(),
                trade.getStopLossPrice(),
                trade.getTarget1Price(),
                trade.getTarget2Price()
        ));
        BigDecimal entryToStop = pctDistance(trade.getEntryPrice(), trade.getStopLossPrice());
        BigDecimal target1Gain = pctGain(trade.getEntryPrice(), trade.getTarget1Price());
        BigDecimal target2Gain = pctGain(trade.getEntryPrice(), trade.getTarget2Price());
        BigDecimal dayHigh = priceFromJson(trade, candidate, "dayHigh", "high", "highestPrice");
        BigDecimal dayLow = priceFromJson(trade, candidate, "dayLow", "low", "lowestPrice");
        return new AnalyzedTrade(trade, forward, candidate, gate, entryToStop, target1Gain, target2Gain, dayHigh, dayLow);
    }

    private Bucket classify(AnalyzedTrade item, List<String> dataGaps) {
        if (RiskRewardShadowGateService.DATA_GAP.equals(item.shadowResult().shadowStatus())) {
            return Bucket.DATA_GAP;
        }
        if (item.entryToStopPct() != null && item.entryToStopPct().compareTo(STOP_TOO_WIDE_PCT) > 0) {
            return Bucket.STOP_TOO_WIDE;
        }
        if (item.target1GainPct() != null && item.target1GainPct().compareTo(TARGET_TOO_CLOSE_PCT) < 0) {
            return Bucket.TARGET_TOO_CLOSE;
        }
        if (isVolatilityMismatch(item)) {
            return Bucket.VOLATILITY_MISMATCH;
        }
        if (isEntryTooChased(item)) {
            return Bucket.ENTRY_TOO_CHASED;
        }
        if (isWeakRegime(item.trade().getEntryRegime())) {
            return Bucket.MARKET_REGIME_WEAK;
        }
        if (isWeakTheme(item)) {
            return Bucket.THEME_WEAK_OR_LOST;
        }
        if (item.dayHigh() == null || item.dayLow() == null) {
            dataGaps.add("DATA_GAP: missing dayHigh/dayLow proxy for volatility bucket symbol="
                    + item.trade().getSymbol() + " date=" + item.trade().getEntryDate());
        }
        return Bucket.OTHER;
    }

    private boolean isEntryTooChased(AnalyzedTrade item) {
        if (item.dayHigh() == null || item.trade().getEntryPrice() == null || item.dayHigh().signum() <= 0) {
            return false;
        }
        BigDecimal ratio = item.trade().getEntryPrice().divide(item.dayHigh(), 6, RoundingMode.HALF_UP);
        return ratio.compareTo(ENTRY_NEAR_DAY_HIGH_RATIO) >= 0;
    }

    private boolean isVolatilityMismatch(AnalyzedTrade item) {
        if (item.dayHigh() == null || item.dayLow() == null || item.trade().getEntryPrice() == null
                || item.trade().getEntryPrice().signum() <= 0) {
            return false;
        }
        BigDecimal rangePct = item.dayHigh().subtract(item.dayLow())
                .divide(item.trade().getEntryPrice(), 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return item.entryToStopPct() != null && rangePct.signum() > 0
                && item.entryToStopPct().compareTo(rangePct.multiply(new BigDecimal("0.8"))) > 0;
    }

    private boolean isWeakRegime(String regime) {
        if (regime == null || regime.isBlank()) return true;
        String value = regime.toUpperCase();
        return "C".equals(value) || value.contains("WEAK") || value.contains("UNKNOWN") || value.contains("DATA_GAP");
    }

    private boolean isWeakTheme(AnalyzedTrade item) {
        String paper = MainstreamThemeNormalizer.normalize(item.trade().getThemeTag(), item.trade().getEntryPayloadJson());
        if (!"UNKNOWN".equals(paper) && !"OTHER".equals(paper)) return false;
        if (item.forward() != null) {
            String forward = MainstreamThemeNormalizer.normalize(item.forward().getThemeTag(), item.forward().getThemeReason());
            if (!"UNKNOWN".equals(forward) && !"OTHER".equals(forward)) return true;
        }
        if (item.candidate() != null) {
            String candidate = MainstreamThemeNormalizer.normalize(item.candidate().getThemeTag(), item.candidate().getReason());
            return "UNKNOWN".equals(candidate) || "OTHER".equals(candidate);
        }
        return true;
    }

    private List<RootCauseBucket> toBucketResponses(Map<Bucket, List<AnalyzedTrade>> buckets, int total) {
        List<RootCauseBucket> responses = new ArrayList<>();
        for (Bucket bucket : Bucket.values()) {
            List<AnalyzedTrade> rows = buckets.get(bucket);
            responses.add(new RootCauseBucket(
                    bucket.name(),
                    rows.size(),
                    pct(rows.size(), total),
                    rows.stream().map(r -> r.trade().getSymbol()).filter(s -> s != null && !s.isBlank()).distinct().limit(5).toList(),
                    bucket.reason
            ));
        }
        return responses;
    }

    private ShadowImpact shadowImpact(List<AnalyzedTrade> analyzed) {
        List<AnalyzedTrade> blocked = analyzed.stream()
                .filter(a -> RiskRewardShadowGateService.FAIL.equals(a.shadowResult().shadowStatus()))
                .toList();
        List<String> gaps = new ArrayList<>();
        Map<String, BigDecimal> avgReturns = new LinkedHashMap<>();
        avgReturns.put("T1", avg(blocked.stream().map(a -> forwardReturn(a, 1)).toList(), 4));
        avgReturns.put("T3", avg(blocked.stream().map(a -> forwardReturn(a, 3)).toList(), 4));
        avgReturns.put("T5", avg(blocked.stream().map(a -> forwardReturn(a, 5)).toList(), 4));
        avgReturns.put("T10", avg(blocked.stream().map(a -> forwardReturn(a, 10)).toList(), 4));
        for (Map.Entry<String, BigDecimal> entry : avgReturns.entrySet()) {
            long missing = blocked.stream().filter(a -> forwardReturn(a, Integer.parseInt(entry.getKey().substring(1))) == null).count();
            if (missing > 0) gaps.add("DATA_GAP: blocked rows missing " + entry.getKey() + " forward return rows=" + missing);
        }
        List<BigDecimal> t5Returns = blocked.stream().map(a -> forwardReturn(a, 5)).filter(v -> v != null).toList();
        BigDecimal winRate = t5Returns.isEmpty() ? null : pct((int) t5Returns.stream().filter(v -> v.compareTo(BigDecimal.ZERO) > 0).count(), t5Returns.size());
        int missedWinners = (int) blocked.stream().filter(this::isMissedWinner).count();
        int avoidedLosers = (int) blocked.stream().filter(this::isAvoidedLoser).count();
        long usableSamples = blocked.stream().filter(a -> bestAvailableReturn(a) != null).count();
        String status = usableSamples < MIN_IMPACT_SAMPLE ? "INSUFFICIENT_SAMPLE" : "OK";
        if (usableSamples < MIN_IMPACT_SAMPLE) {
            gaps.add("INSUFFICIENT_SAMPLE: blocked rows with any forward return=" + usableSamples + ", min=" + MIN_IMPACT_SAMPLE);
        }
        return new ShadowImpact(
                blocked.size(),
                pct(blocked.size(), analyzed.size()),
                avgReturns.get("T1"),
                avgReturns.get("T3"),
                avgReturns.get("T5"),
                avgReturns.get("T10"),
                winRate,
                missedWinners,
                avoidedLosers,
                status,
                gaps
        );
    }

    private boolean isMissedWinner(AnalyzedTrade item) {
        BigDecimal value = bestAvailableReturn(item);
        return value != null && value.compareTo(MISSED_WINNER_PCT) >= 0;
    }

    private boolean isAvoidedLoser(AnalyzedTrade item) {
        BigDecimal value = bestAvailableReturn(item);
        return value != null && value.compareTo(BigDecimal.ZERO) < 0;
    }

    private BigDecimal bestAvailableReturn(AnalyzedTrade item) {
        BigDecimal t10 = forwardReturn(item, 10);
        if (t10 != null) return t10;
        BigDecimal t5 = forwardReturn(item, 5);
        if (t5 != null) return t5;
        BigDecimal t3 = forwardReturn(item, 3);
        if (t3 != null) return t3;
        return forwardReturn(item, 1);
    }

    private BigDecimal forwardReturn(AnalyzedTrade item, int horizon) {
        return switch (horizon) {
            case 1 -> item.trade().getReturn1d() != null ? item.trade().getReturn1d()
                    : item.forward() == null ? null : item.forward().getT1CloseReturnPct();
            case 3 -> item.trade().getReturn3d() != null ? item.trade().getReturn3d()
                    : item.forward() == null ? null : item.forward().getT3CloseReturnPct();
            case 5 -> item.trade().getReturn5d() != null ? item.trade().getReturn5d()
                    : item.forward() == null ? null : item.forward().getT5CloseReturnPct();
            case 10 -> item.trade().getReturn10d() != null ? item.trade().getReturn10d()
                    : item.forward() == null ? null : item.forward().getT10CloseReturnPct();
            default -> null;
        };
    }

    private BigDecimal priceFromJson(PaperTradeEntity trade, CandidateStockEntity candidate, String... fieldNames) {
        BigDecimal value = priceFromJsonText(trade.getEntryPayloadJson(), fieldNames);
        if (value != null) return value;
        value = priceFromJsonText(trade.getPayloadJson(), fieldNames);
        if (value != null) return value;
        return candidate == null ? null : priceFromJsonText(candidate.getPayloadJson(), fieldNames);
    }

    private BigDecimal priceFromJsonText(String json, String... fieldNames) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            for (String field : fieldNames) {
                BigDecimal value = findDecimal(root, field);
                if (value != null) return value;
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private BigDecimal findDecimal(JsonNode node, String fieldName) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            JsonNode direct = node.get(fieldName);
            if (direct != null && direct.isNumber()) return direct.decimalValue();
            var fields = node.fields();
            while (fields.hasNext()) {
                BigDecimal nested = findDecimal(fields.next().getValue(), fieldName);
                if (nested != null) return nested;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                BigDecimal nested = findDecimal(child, fieldName);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private BigDecimal pctDistance(BigDecimal entry, BigDecimal stop) {
        if (entry == null || stop == null || entry.signum() <= 0) return null;
        return entry.subtract(stop).abs().divide(entry, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal pctGain(BigDecimal entry, BigDecimal target) {
        if (entry == null || target == null || entry.signum() <= 0) return null;
        return target.subtract(entry).divide(entry, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal avg(List<BigDecimal> values, int scale) {
        List<BigDecimal> usable = values.stream().filter(v -> v != null).toList();
        if (usable.isEmpty()) return null;
        BigDecimal sum = usable.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(usable.size()), scale, RoundingMode.HALF_UP);
    }

    private BigDecimal pct(int count, int total) {
        if (total <= 0) return BigDecimal.ZERO;
        return BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private String traceKey(LocalDate date, String symbol) {
        return (date == null ? "" : date.toString()) + "|" + (symbol == null ? "" : symbol);
    }

    private enum Bucket {
        ENTRY_TOO_CHASED("entry 接近日高或追價，使可用 upside 相對 risk 偏弱。"),
        STOP_TOO_WIDE("entry 到 stop 距離過大，導致 RR 被風險端稀釋。"),
        TARGET_TOO_CLOSE("target1/target2 距 entry 太近，reward 端不足。"),
        VOLATILITY_MISMATCH("日內區間 proxy 與 stop/target 結構不匹配；ATR 缺失時先用 dayHigh/dayLow proxy。"),
        MARKET_REGIME_WEAK("market regime 弱或 UNKNOWN 時仍出現低 RR trade。"),
        THEME_WEAK_OR_LOST("theme trace 弱、UNKNOWN、OTHER，或 repair 後仍缺乏主流題材證據。"),
        DATA_GAP("必要價格欄位缺失，無法得出可信 RR 結論。"),
        OTHER("未命中主要 bucket，需補更多價量或決策 trace。");

        private final String reason;

        Bucket(String reason) {
            this.reason = reason;
        }
    }

    private record AnalyzedTrade(
            PaperTradeEntity trade,
            CandidateForwardTrackingEntity forward,
            CandidateStockEntity candidate,
            RiskRewardShadowGateResult shadowResult,
            BigDecimal entryToStopPct,
            BigDecimal target1GainPct,
            BigDecimal target2GainPct,
            BigDecimal dayHigh,
            BigDecimal dayLow
    ) {
    }
}
