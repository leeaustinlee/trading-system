package com.austin.trading.service;

import com.austin.trading.dto.request.TradingStateUpsertRequest;
import com.austin.trading.dto.response.TradingStateResponse;
import com.austin.trading.entity.TradingStateEntity;
import com.austin.trading.repository.TradingStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

@Service
public class TradingStateService {

    private static final Logger log = LoggerFactory.getLogger(TradingStateService.class);

    private final TradingStateRepository tradingStateRepository;

    public TradingStateService(TradingStateRepository tradingStateRepository) {
        this.tradingStateRepository = tradingStateRepository;
    }

    /**
     * v2.4：盤前/開盤初期必須使用此方法，避免跨日污染。
     * <p>
     * 若今天尚無 trading_state，回傳 empty。呼叫方應以「安全預設」初始化：
     * {@code decisionLock=NONE}、{@code timeDecayStage=依現在時間算}。
     * </p>
     */
    public Optional<TradingStateResponse> getTodayState() {
        return getStateByDate(LocalDate.now());
    }

    public Optional<TradingStateResponse> getStateByDate(LocalDate tradingDate) {
        return tradingStateRepository.findTopByTradingDateOrderByUpdatedAtDesc(tradingDate)
                .map(this::toResponse);
    }

    /**
     * @deprecated v2.4：會跨日污染。請改用 {@link #getTodayState()} 或 {@link #getStateByDate(LocalDate)}。
     * 保留以兼容現有呼叫點，但會 log 警告。
     */
    @Deprecated
    public Optional<TradingStateResponse> getCurrentState() {
        Optional<TradingStateResponse> today = getTodayState();
        if (today.isPresent()) return today;
        // 若今天沒有 state，不再回傳昨天 — 直接回 empty，讓呼叫方用安全預設初始化。
        log.debug("[TradingStateService] getCurrentState(): 今日尚無 state，不退回昨日；請呼叫方用安全預設");
        return Optional.empty();
    }

    /**
     * v2.4：依現在時間算 timeDecayStage，供「今天無 state 時的 fallback」與 engines 重算用。
     */
    public static String resolveTimeDecayForNow() {
        return resolveTimeDecay(LocalTime.now());
    }

    public static String resolveTimeDecay(LocalTime time) {
        LocalTime now = time == null ? LocalTime.now() : time;
        if (now.isBefore(LocalTime.of(10, 0)))  return "EARLY";
        if (now.isBefore(LocalTime.of(10, 30))) return "MID";
        return "LATE";
    }

    public List<TradingStateResponse> getHistory(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return tradingStateRepository.findAllByOrderByTradingDateDescUpdatedAtDesc(PageRequest.of(0, safeLimit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public TradingStateResponse create(TradingStateUpsertRequest request) {
        TradingStateEntity entity = new TradingStateEntity();
        entity.setTradingDate(request.tradingDate());
        entity.setMarketGrade(request.marketGrade());
        entity.setDecisionLock(request.decisionLock());
        entity.setTimeDecayStage(request.timeDecayStage());
        entity.setHourlyGate(request.hourlyGate());
        entity.setMonitorMode(request.monitorMode());
        entity.setPayloadJson(request.payloadJson());
        return toResponse(tradingStateRepository.save(entity));
    }

    private TradingStateResponse toResponse(TradingStateEntity entity) {
        return new TradingStateResponse(
                entity.getId(),
                entity.getTradingDate(),
                entity.getMarketGrade(),
                entity.getDecisionLock(),
                entity.getTimeDecayStage(),
                entity.getHourlyGate(),
                entity.getMonitorMode(),
                entity.getPayloadJson(),
                entity.getUpdatedAt()
        );
    }
}
