package com.austin.trading.scheduler;

import com.austin.trading.client.TwseMisClient;
import com.austin.trading.client.dto.StockQuote;
import com.austin.trading.engine.ChasedHighEntryEngine;
import com.austin.trading.engine.ChasedHighEntryEngine.ChasedEntryInput;
import com.austin.trading.engine.ReviewScoringEngine;
import com.austin.trading.engine.ReviewScoringEngine.ReviewRequest;
import com.austin.trading.engine.ReviewScoringEngine.ReviewResult;
import com.austin.trading.notify.LineTemplateService;
import com.austin.trading.repository.CandidateStockRepository;
import com.austin.trading.repository.PositionRepository;
import com.austin.trading.service.FinalDecisionService;
import com.austin.trading.service.MarketDataService;
import com.austin.trading.service.SchedulerLogService;
import com.austin.trading.service.TradingStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 14:00 交易檢討排程。
 * 以當日最終決策、市場狀態為基礎，執行 ReviewScoringEngine，並發送 LINE 通知。
 */
@Component
@ConditionalOnProperty(prefix = "trading.scheduler.aftermarket-review", name = "enabled", havingValue = "true")
public class AftermarketReview1400Job {

    private static final Logger log = LoggerFactory.getLogger(AftermarketReview1400Job.class);

    /** 當日損益虧損超過此門檻視為「超過日損限制」（元） */
    private static final BigDecimal DAILY_LOSS_THRESHOLD = new BigDecimal("-5000");
    /** 視為追高的門檻：進場價落在日高 0.5% 以內。 */
    private static final double CHASED_HIGH_THRESHOLD = 0.005;

    private final MarketDataService marketDataService;
    private final TradingStateService tradingStateService;
    private final FinalDecisionService finalDecisionService;
    private final ReviewScoringEngine reviewScoringEngine;
    private final LineTemplateService lineTemplateService;
    private final SchedulerLogService schedulerLogService;
    private final PositionRepository positionRepository;
    private final CandidateStockRepository candidateStockRepository;
    private final TwseMisClient twseMisClient;
    private final ChasedHighEntryEngine chasedHighEntryEngine;

    public AftermarketReview1400Job(
            MarketDataService marketDataService,
            TradingStateService tradingStateService,
            FinalDecisionService finalDecisionService,
            ReviewScoringEngine reviewScoringEngine,
            LineTemplateService lineTemplateService,
            SchedulerLogService schedulerLogService,
            PositionRepository positionRepository,
            CandidateStockRepository candidateStockRepository,
            TwseMisClient twseMisClient,
            ChasedHighEntryEngine chasedHighEntryEngine
    ) {
        this.marketDataService = marketDataService;
        this.tradingStateService = tradingStateService;
        this.finalDecisionService = finalDecisionService;
        this.reviewScoringEngine = reviewScoringEngine;
        this.lineTemplateService = lineTemplateService;
        this.schedulerLogService = schedulerLogService;
        this.positionRepository = positionRepository;
        this.candidateStockRepository = candidateStockRepository;
        this.twseMisClient = twseMisClient;
        this.chasedHighEntryEngine = chasedHighEntryEngine;
    }

    @Scheduled(cron = "${trading.scheduler.aftermarket-review-cron:0 0 14 * * MON-FRI}",
               zone = "${trading.timezone:Asia/Taipei}")
    public void run() {
        LocalDateTime triggerTime = LocalDateTime.now();
        String jobName = "AftermarketReview1400Job";
        try {
            LocalDate today = LocalDate.now();

            // 讀取今日狀態
            var market = marketDataService.getCurrentMarket().orElse(null);
            var state  = tradingStateService.getCurrentState().orElse(null);
            var decision = finalDecisionService.getCurrent().orElse(null);

            String actualGrade  = market  == null ? "B" : safe(market.marketGrade(), "B");
            String decidedGrade = state   == null ? actualGrade : safe(state.marketGrade(), actualGrade);
            String decisionType = decision == null ? "WATCH" : safe(decision.decision(), "WATCH");

            // 讀取今日已關閉持倉，判斷實際損益狀況
            LocalDateTime dayStart = today.atStartOfDay();
            LocalDateTime dayEnd   = today.atTime(LocalTime.MAX);
            boolean hadLoss         = hasAnyLoss(dayStart, dayEnd);
            boolean exceededDailyLoss = exceedsDailyLoss(dayStart, dayEnd);
            boolean chasedHighEntry = detectChasedHighEntry(today, dayStart, dayEnd);

            ReviewRequest request = new ReviewRequest(
                    decidedGrade,
                    actualGrade,
                    decisionType,
                    hadLoss,
                    true,               // stopLossExecuted：有關倉即視為已執行停損流程
                    chasedHighEntry,
                    exceededDailyLoss
            );

            ReviewResult result = reviewScoringEngine.evaluate(request);
            lineTemplateService.notifyReview1400(result.summary(), today);

            log.info("[AftermarketReview1400Job] score={}, compliance={}", result.score(), result.compliance());
            schedulerLogService.success(jobName, triggerTime, LocalDateTime.now(),
                    "score=" + result.score());
        } catch (Exception e) {
            schedulerLogService.failed(jobName, triggerTime, LocalDateTime.now(), e.getMessage());
            throw e;
        }
    }

    private boolean hasAnyLoss(LocalDateTime start, LocalDateTime end) {
        try {
            return positionRepository.findClosedBetween(start, end)
                    .stream()
                    .anyMatch(p -> p.getRealizedPnl() != null
                            && p.getRealizedPnl().compareTo(BigDecimal.ZERO) < 0);
        } catch (Exception e) {
            log.warn("[AftermarketReview1400Job] hasAnyLoss query failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean exceedsDailyLoss(LocalDateTime start, LocalDateTime end) {
        try {
            BigDecimal total = positionRepository.sumRealizedPnlBetween(start, end);
            return total != null && total.compareTo(DAILY_LOSS_THRESHOLD) < 0;
        } catch (Exception e) {
            log.warn("[AftermarketReview1400Job] sumRealizedPnl query failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 追高判斷：
     * 以當日開倉持倉（LONG）平均成本，對照 MIS 日高。
     * 若 entry >= dayHigh * (1 - 0.5%)，視為追高進場。
     */
    private boolean detectChasedHighEntry(LocalDate today, LocalDateTime start, LocalDateTime end) {
        try {
            List<com.austin.trading.entity.PositionEntity> openedToday =
                    positionRepository.findHistoryByFilter(null, start, end, PageRequest.of(0, 300));
            if (openedToday.isEmpty()) {
                return false;
            }

            List<com.austin.trading.entity.PositionEntity> longPositions = openedToday.stream()
                    .filter(p -> p.getSymbol() != null && p.getAvgCost() != null)
                    .filter(p -> p.getSide() == null || "LONG".equalsIgnoreCase(p.getSide()))
                    .toList();
            if (longPositions.isEmpty()) {
                return false;
            }

            List<String> symbols = longPositions.stream()
                    .map(com.austin.trading.entity.PositionEntity::getSymbol)
                    .distinct()
                    .toList();

            Map<String, Double> dayHighMap = new HashMap<>();
            for (StockQuote q : twseMisClient.getTseQuotes(symbols)) {
                if (q.dayHigh() != null) dayHighMap.put(q.symbol(), q.dayHigh());
            }

            // TSE 沒抓到的，再嘗試 OTC。
            List<String> unresolved = symbols.stream()
                    .filter(s -> !dayHighMap.containsKey(s))
                    .collect(Collectors.toList());
            if (!unresolved.isEmpty()) {
                for (StockQuote q : twseMisClient.getOtcQuotes(unresolved)) {
                    if (q.dayHigh() != null) dayHighMap.put(q.symbol(), q.dayHigh());
                }
            }

            // 即時資料抓不到時，退回 candidate payload 的 day_high（若有）
            if (dayHighMap.isEmpty()) {
                candidateStockRepository.findByTradingDateOrderByScoreDesc(today, PageRequest.of(0, 200))
                        .forEach(c -> {
                            Double dayHigh = extractDayHigh(c.getPayloadJson());
                            if (dayHigh != null) {
                                dayHighMap.put(c.getSymbol(), dayHigh);
                            }
                        });
            }

            List<ChasedEntryInput> inputs = new ArrayList<>();
            for (com.austin.trading.entity.PositionEntity p : longPositions) {
                Double dayHigh = dayHighMap.get(p.getSymbol());
                if (dayHigh == null) continue;
                inputs.add(new ChasedEntryInput(p.getSymbol(), p.getAvgCost().doubleValue(), dayHigh));
            }

            return chasedHighEntryEngine.hasChasedEntry(inputs, CHASED_HIGH_THRESHOLD);
        } catch (Exception e) {
            log.warn("[AftermarketReview1400Job] chasedHigh detection failed: {}", e.getMessage());
            return false;
        }
    }

    private Double extractDayHigh(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) return null;
        String token = "\"day_high\":";
        int start = payloadJson.indexOf(token);
        if (start < 0) return null;
        int from = start + token.length();
        int end = from;
        while (end < payloadJson.length()) {
            char c = payloadJson.charAt(end);
            if ((c >= '0' && c <= '9') || c == '.' || c == '-') {
                end++;
                continue;
            }
            break;
        }
        if (end <= from) return null;
        try {
            return Double.parseDouble(payloadJson.substring(from, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String safe(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
