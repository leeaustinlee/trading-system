package com.austin.trading.service;

import com.austin.trading.entity.MarketIndexDailyEntity;
import com.austin.trading.repository.MarketIndexDailyRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class DailyTechnicalService {

    private final MarketIndexDailyRepository repository;

    public DailyTechnicalService(MarketIndexDailyRepository repository) {
        this.repository = repository;
    }

    public TechnicalSnapshot snapshot(String symbol, LocalDate asOf) {
        if (symbol == null || asOf == null) return TechnicalSnapshot.empty(List.of("DATA_GAP: symbol/asOf missing"));
        List<MarketIndexDailyEntity> desc = repository.findLatestBySymbolBefore(symbol, asOf, PageRequest.of(0, 25));
        if (desc.isEmpty()) return TechnicalSnapshot.empty(List.of("DATA_GAP: no daily bars for " + symbol));
        List<MarketIndexDailyEntity> bars = new ArrayList<>(desc);
        Collections.reverse(bars);
        List<String> gaps = new ArrayList<>();
        BigDecimal ma5 = ma(bars, 5, 0, gaps);
        BigDecimal ma10 = ma(bars, 10, 0, gaps);
        BigDecimal ma20 = ma(bars, 20, 0, gaps);
        BigDecimal ma5Prev = ma(bars, 5, 1, gaps);
        BigDecimal previousLow = previousLow(bars, 10);
        BigDecimal recentHigh = recentHigh(bars, 20);
        BigDecimal atr = atr(bars, 14, gaps);
        BigDecimal volumeRatio = volumeRatio(bars, 5, gaps);
        BigDecimal return5d = returnPct(bars, 5, gaps);
        BigDecimal return10d = returnPct(bars, 10, gaps);
        return new TechnicalSnapshot(ma5, ma10, ma20, ma5Prev, previousLow, recentHigh, atr, volumeRatio,
                return5d, return10d, gaps.stream().distinct().toList());
    }

    private BigDecimal ma(List<MarketIndexDailyEntity> bars, int n, int offset, List<String> gaps) {
        int endExclusive = bars.size() - offset;
        int start = endExclusive - n;
        if (start < 0 || endExclusive > bars.size()) {
            gaps.add("DATA_GAP: MA" + n + " requires " + (n + offset) + " bars");
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = start; i < endExclusive; i++) {
            BigDecimal close = bars.get(i).getClosePrice();
            if (close == null) {
                gaps.add("DATA_GAP: close missing for MA" + n);
                return null;
            }
            sum = sum.add(close);
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal previousLow(List<MarketIndexDailyEntity> bars, int lookback) {
        if (bars.size() < 2) return null;
        int start = Math.max(0, bars.size() - 1 - lookback);
        BigDecimal min = null;
        for (int i = start; i < bars.size() - 1; i++) {
            BigDecimal low = bars.get(i).getLowPrice();
            if (low != null && (min == null || low.compareTo(min) < 0)) min = low;
        }
        return min;
    }

    private BigDecimal recentHigh(List<MarketIndexDailyEntity> bars, int lookback) {
        int start = Math.max(0, bars.size() - lookback);
        BigDecimal max = null;
        for (int i = start; i < bars.size(); i++) {
            BigDecimal high = bars.get(i).getHighPrice();
            if (high != null && (max == null || high.compareTo(max) > 0)) max = high;
        }
        return max;
    }

    private BigDecimal atr(List<MarketIndexDailyEntity> bars, int n, List<String> gaps) {
        if (bars.size() < n + 1) {
            gaps.add("DATA_GAP: ATR" + n + " requires " + (n + 1) + " bars");
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = bars.size() - n; i < bars.size(); i++) {
            MarketIndexDailyEntity b = bars.get(i);
            BigDecimal prevClose = bars.get(i - 1).getClosePrice();
            if (b.getHighPrice() == null || b.getLowPrice() == null || prevClose == null) {
                gaps.add("DATA_GAP: high/low/prevClose missing for ATR");
                return null;
            }
            BigDecimal tr = b.getHighPrice().subtract(b.getLowPrice()).abs()
                    .max(b.getHighPrice().subtract(prevClose).abs())
                    .max(b.getLowPrice().subtract(prevClose).abs());
            sum = sum.add(tr);
        }
        return sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal volumeRatio(List<MarketIndexDailyEntity> bars, int n, List<String> gaps) {
        if (bars.size() < n + 1) {
            gaps.add("DATA_GAP: volume ratio requires " + (n + 1) + " bars");
            return null;
        }
        Long current = bars.get(bars.size() - 1).getVolume();
        if (current == null || current <= 0) {
            gaps.add("DATA_GAP: current volume missing");
            return null;
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = bars.size() - 1 - n; i < bars.size() - 1; i++) {
            Long v = bars.get(i).getVolume();
            if (v == null || v <= 0) {
                gaps.add("DATA_GAP: historical volume missing");
                return null;
            }
            sum = sum.add(BigDecimal.valueOf(v));
        }
        BigDecimal avg = sum.divide(BigDecimal.valueOf(n), 6, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(current).divide(avg, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal returnPct(List<MarketIndexDailyEntity> bars, int n, List<String> gaps) {
        if (bars.size() < n + 1) {
            gaps.add("DATA_GAP: return" + n + "d requires " + (n + 1) + " bars");
            return null;
        }
        BigDecimal start = bars.get(bars.size() - 1 - n).getClosePrice();
        BigDecimal end = bars.get(bars.size() - 1).getClosePrice();
        if (start == null || end == null || start.signum() == 0) return null;
        return end.subtract(start).divide(start, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP);
    }

    public record TechnicalSnapshot(
            BigDecimal ma5,
            BigDecimal ma10,
            BigDecimal ma20,
            BigDecimal ma5Previous,
            BigDecimal previousLow,
            BigDecimal recentHigh,
            BigDecimal atr,
            BigDecimal volumeRatio,
            BigDecimal return5d,
            BigDecimal return10d,
            List<String> dataGaps
    ) {
        static TechnicalSnapshot empty(List<String> gaps) {
            return new TechnicalSnapshot(null, null, null, null, null, null, null, null, null, null, gaps);
        }
    }
}
