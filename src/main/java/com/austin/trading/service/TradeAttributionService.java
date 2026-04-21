package com.austin.trading.service;

import com.austin.trading.dto.internal.TradeAttributionInput;
import com.austin.trading.dto.internal.TradeAttributionOutput;
import com.austin.trading.engine.TradeAttributionEngine;
import com.austin.trading.engine.ThemeSelectionEngine;
import com.austin.trading.entity.PositionEntity;
import com.austin.trading.entity.TradeAttributionEntity;
import com.austin.trading.entity.TradeReviewEntity;
import com.austin.trading.repository.ExecutionDecisionLogRepository;
import com.austin.trading.repository.ExecutionTimingDecisionRepository;
import com.austin.trading.repository.MarketRegimeDecisionRepository;
import com.austin.trading.repository.SetupDecisionRepository;
import com.austin.trading.repository.ThemeStrengthDecisionRepository;
import com.austin.trading.repository.TradeAttributionRepository;
import com.austin.trading.repository.TradeReviewRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * P1.2 Trade Attribution Layer.
 *
 * <p>For each closed position, looks up upstream pipeline decisions (regime, setup,
 * timing, theme), computes attribution metrics, and persists one row to
 * {@code trade_attribution}.</p>
 *
 * <p>Triggered by {@link TradeReviewService} after review generation, or by batch
 * job for backfill.</p>
 */
@Service
public class TradeAttributionService {

    private static final Logger log = LoggerFactory.getLogger(TradeAttributionService.class);

    private final TradeAttributionEngine          engine;
    private final TradeAttributionRepository      attributionRepo;
    private final MarketRegimeDecisionRepository  regimeRepo;
    private final SetupDecisionRepository         setupRepo;
    private final ExecutionTimingDecisionRepository timingRepo;
    private final ThemeStrengthDecisionRepository themeRepo;
    private final ExecutionDecisionLogRepository  execRepo;
    private final TradeReviewRepository           reviewRepo;
    private final ThemeSelectionEngine            themeSelectionEngine;

    public TradeAttributionService(
            TradeAttributionEngine engine,
            TradeAttributionRepository attributionRepo,
            MarketRegimeDecisionRepository regimeRepo,
            SetupDecisionRepository setupRepo,
            ExecutionTimingDecisionRepository timingRepo,
            ThemeStrengthDecisionRepository themeRepo,
            ExecutionDecisionLogRepository execRepo,
            TradeReviewRepository reviewRepo,
            ThemeSelectionEngine themeSelectionEngine) {
        this.engine              = engine;
        this.attributionRepo     = attributionRepo;
        this.regimeRepo          = regimeRepo;
        this.setupRepo           = setupRepo;
        this.timingRepo          = timingRepo;
        this.themeRepo           = themeRepo;
        this.execRepo            = execRepo;
        this.reviewRepo          = reviewRepo;
        this.themeSelectionEngine = themeSelectionEngine;
    }

    /**
     * Compute and persist attribution for a single closed position.
     * Idempotent — skips if attribution already exists for positionId.
     */
    @Transactional
    public Optional<TradeAttributionOutput> computeForPosition(PositionEntity pos) {
        if (!"CLOSED".equals(pos.getStatus())) return Optional.empty();
        if (pos.getId() != null && attributionRepo.findByPositionId(pos.getId()).isPresent()) {
            log.debug("[TradeAttribution] skipping posId={} — already attributed", pos.getId());
            return Optional.empty();
        }

        LocalDate entryDate = pos.getOpenedAt() != null ? pos.getOpenedAt().toLocalDate() : null;
        if (entryDate == null) {
            log.warn("[TradeAttribution] posId={} skipped — no openedAt", pos.getId());
            return Optional.empty();
        }

        TradeAttributionInput in = buildInput(pos, entryDate);
        TradeAttributionOutput out = engine.evaluate(in);
        if (out == null) return Optional.empty();

        attributionRepo.save(toEntity(out));
        log.info("[TradeAttribution] {} timing={} exit={} delay={}",
                pos.getSymbol(), out.timingQuality(), out.exitQuality(),
                out.delayPct() != null ? out.delayPct().toPlainString() + "%" : "N/A");
        return Optional.of(out);
    }

    /**
     * Backfill attribution for all CLOSED positions that lack a record.
     */
    @Transactional
    public int backfillAll(List<PositionEntity> closedPositions) {
        int count = 0;
        for (PositionEntity pos : closedPositions) {
            if (computeForPosition(pos).isPresent()) count++;
        }
        log.info("[TradeAttribution] backfill complete: {} new records", count);
        return count;
    }

    /** Read attribution for a single position. */
    public Optional<TradeAttributionOutput> findForPosition(Long positionId) {
        return attributionRepo.findByPositionId(positionId).map(this::toOutput);
    }

    /** All attributions ordered by entry date desc (weekly learning feed). */
    public List<TradeAttributionOutput> findAll() {
        return attributionRepo.findAllOrderByEntryDateDesc().stream()
                .map(this::toOutput).toList();
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private TradeAttributionInput buildInput(PositionEntity pos, LocalDate entryDate) {
        String sym = pos.getSymbol();

        // Regime
        var regimeOpt = regimeRepo.findTopByTradingDateOrderByEvaluatedAtDescIdDesc(entryDate);
        Long regimeId   = regimeOpt.map(e -> e.getId()).orElse(null);
        String regimeType = regimeOpt.map(e -> e.getRegimeType()).orElse(null);

        // Setup (prefer valid)
        var setupOpt = setupRepo.findTopByTradingDateAndSymbolAndValidTrueOrderByIdDesc(entryDate, sym)
                .or(() -> setupRepo.findTopByTradingDateAndSymbolOrderByIdDesc(entryDate, sym));
        Long setupId       = setupOpt.map(e -> e.getId()).orElse(null);
        String setupType   = setupOpt.map(e -> e.getSetupType()).orElse(null);
        var idealEntry     = setupOpt.map(e -> e.getIdealEntryPrice()).orElse(null);

        // Timing
        var timingOpt = timingRepo.findTopByTradingDateAndSymbolOrderByIdDesc(entryDate, sym);
        Long timingId     = timingOpt.map(e -> e.getId()).orElse(null);
        String timingMode = timingOpt.map(e -> e.getTimingMode()).orElse(null);

        // Theme + ThemeStrengthDecision
        String themeTag = themeSelectionEngine.getLeadingThemeForStock(sym, entryDate).orElse(null);
        Long themeDecId    = null;
        String themeStage  = null;
        if (themeTag != null) {
            var themeOpt = themeRepo.findTopByTradingDateAndThemeTagOrderByIdDesc(entryDate, themeTag);
            themeDecId  = themeOpt.map(e -> e.getId()).orElse(null);
            themeStage  = themeOpt.map(e -> e.getThemeStage()).orElse(null);
        }

        // Execution decision
        Long execId = execRepo.findTopByTradingDateAndSymbolOrderByIdDesc(entryDate, sym)
                .map(e -> e.getId()).orElse(null);

        // MFE/MAE from TradeReview if available
        Optional<TradeReviewEntity> reviewOpt = pos.getId() != null
                ? reviewRepo.findTopByPositionIdOrderByReviewVersionDesc(pos.getId())
                : Optional.empty();
        BigDecimal mfePct = reviewOpt.map(TradeReviewEntity::getMfePct).orElse(null);
        BigDecimal maePct = reviewOpt.map(TradeReviewEntity::getMaePct).orElse(null);

        return new TradeAttributionInput(
                pos, regimeId, regimeType,
                setupId, setupType, idealEntry,
                timingId, timingMode,
                themeDecId, themeTag, themeStage,
                execId, mfePct, maePct, null);
    }

    private TradeAttributionEntity toEntity(TradeAttributionOutput out) {
        TradeAttributionEntity e = new TradeAttributionEntity();
        e.setPositionId(out.positionId());
        e.setSymbol(out.symbol());
        e.setEntryDate(out.entryDate());
        e.setExitDate(out.exitDate());
        e.setSetupType(out.setupType());
        e.setRegimeType(out.regimeType());
        e.setThemeStage(out.themeStage());
        e.setTimingMode(out.timingMode());
        e.setIdealEntryPrice(out.idealEntryPrice());
        e.setActualEntryPrice(out.actualEntryPrice());
        e.setDelayPct(out.delayPct());
        e.setMfePct(out.mfePct());
        e.setMaePct(out.maePct());
        e.setPnlPct(out.pnlPct());
        e.setTimingQuality(out.timingQuality());
        e.setExitQuality(out.exitQuality());
        e.setSizingQuality(out.sizingQuality());
        e.setRegimeDecisionId(out.regimeDecisionId());
        e.setSetupDecisionId(out.setupDecisionId());
        e.setTimingDecisionId(out.timingDecisionId());
        e.setThemeDecisionId(out.themeDecisionId());
        e.setExecutionDecisionId(out.executionDecisionId());
        e.setPayloadJson(out.payloadJson());
        return e;
    }

    private TradeAttributionOutput toOutput(TradeAttributionEntity e) {
        return new TradeAttributionOutput(
                e.getPositionId(), e.getSymbol(),
                e.getEntryDate(), e.getExitDate(),
                e.getSetupType(), e.getRegimeType(), e.getThemeStage(), e.getTimingMode(),
                e.getIdealEntryPrice(), e.getActualEntryPrice(), e.getDelayPct(),
                e.getMfePct(), e.getMaePct(), e.getPnlPct(),
                e.getTimingQuality(), e.getExitQuality(), e.getSizingQuality(),
                e.getRegimeDecisionId(), e.getSetupDecisionId(),
                e.getTimingDecisionId(), e.getThemeDecisionId(), e.getExecutionDecisionId(),
                e.getPayloadJson());
    }
}
