package com.austin.trading.config;

import com.austin.trading.entity.MarketSnapshotEntity;
import com.austin.trading.entity.NotificationLogEntity;
import com.austin.trading.entity.TradingStateEntity;
import com.austin.trading.repository.MarketSnapshotRepository;
import com.austin.trading.repository.NotificationLogRepository;
import com.austin.trading.repository.TradingStateRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Component
@Profile("local")
@ConditionalOnProperty(prefix = "trading.mock-data-loader", name = "enabled", havingValue = "true")
public class LocalMockDataLoader implements CommandLineRunner {

    private final MarketSnapshotRepository marketSnapshotRepository;
    private final TradingStateRepository tradingStateRepository;
    private final NotificationLogRepository notificationLogRepository;

    public LocalMockDataLoader(
            MarketSnapshotRepository marketSnapshotRepository,
            TradingStateRepository tradingStateRepository,
            NotificationLogRepository notificationLogRepository
    ) {
        this.marketSnapshotRepository = marketSnapshotRepository;
        this.tradingStateRepository = tradingStateRepository;
        this.notificationLogRepository = notificationLogRepository;
    }

    @Override
    public void run(String... args) {
        seedMarketSnapshot();
        seedTradingState();
        seedNotification();
    }

    private void seedMarketSnapshot() {
        if (marketSnapshotRepository.count() > 0) {
            return;
        }
        MarketSnapshotEntity snapshot = new MarketSnapshotEntity();
        snapshot.setTradingDate(LocalDate.now());
        snapshot.setMarketGrade("B");
        snapshot.setMarketPhase("高檔震盪期");
        snapshot.setDecision("WATCH");
        snapshot.setPayloadJson("{\"source\":\"local-seed\",\"note\":\"phase1 mock data\"}");
        marketSnapshotRepository.save(snapshot);
    }

    private void seedTradingState() {
        if (tradingStateRepository.count() > 0) {
            return;
        }
        TradingStateEntity state = new TradingStateEntity();
        state.setTradingDate(LocalDate.now());
        state.setMarketGrade("B");
        state.setDecisionLock("NONE");
        state.setTimeDecayStage("EARLY");
        state.setHourlyGate("ON");
        state.setMonitorMode("WATCH");
        state.setPayloadJson("{\"source\":\"local-seed\",\"monitor\":\"enabled\"}");
        tradingStateRepository.save(state);
    }

    private void seedNotification() {
        if (notificationLogRepository.count() > 0) {
            return;
        }
        NotificationLogEntity notification = new NotificationLogEntity();
        notification.setEventTime(LocalDateTime.now());
        notification.setNotificationType("SYSTEM");
        notification.setSource("Codex");
        notification.setTitle("Trading System Local Seed");
        notification.setContent("Phase 1 mock data loaded for local verification.");
        notification.setPayloadJson("{\"source\":\"local-seed\",\"phase\":1}");
        notificationLogRepository.save(notification);
    }
}
