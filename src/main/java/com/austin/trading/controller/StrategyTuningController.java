package com.austin.trading.controller;

import com.austin.trading.dto.response.StrategyTuningRecommendationDto;
import com.austin.trading.dto.response.StrategyTuningSummaryDto;
import com.austin.trading.dto.response.TuningEvaluationDetailDto;
import com.austin.trading.dto.response.TuningEvaluationResultDto;
import com.austin.trading.dto.response.TuningEvaluationSummaryDto;
import com.austin.trading.service.StrategyTuningService;
import com.austin.trading.service.TuningEvaluationQueryService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/strategy-tuning")
public class StrategyTuningController {
    private final StrategyTuningService service;
    private final TuningEvaluationQueryService evaluationQueryService;

    public StrategyTuningController(StrategyTuningService service,
                                    TuningEvaluationQueryService evaluationQueryService) {
        this.service = service;
        this.evaluationQueryService = evaluationQueryService;
    }

    @PostMapping("/generate")
    public List<StrategyTuningRecommendationDto> generate(@RequestParam(defaultValue = "20") int lookbackDays) {
        return service.generateRecommendations(LocalDate.now(), lookbackDays);
    }

    @GetMapping("/recommendations")
    public List<StrategyTuningRecommendationDto> list() { return service.listRecommendations(); }

    @GetMapping("/recommendations/{id}")
    public StrategyTuningRecommendationDto get(@PathVariable Long id) { return service.getRecommendation(id); }

    @PostMapping("/recommendations/{id}/approve")
    public StrategyTuningRecommendationDto approve(@PathVariable Long id,
                                                   @RequestParam(defaultValue = "Austin") String approvedBy) {
        return service.approveRecommendation(id, approvedBy);
    }

    @PostMapping("/recommendations/{id}/reject")
    public StrategyTuningRecommendationDto reject(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "Austin") String rejectedBy,
                                                  @RequestParam(required = false) String reason) {
        return service.rejectRecommendation(id, rejectedBy, reason);
    }

    @PostMapping("/recommendations/{id}/apply")
    public StrategyTuningRecommendationDto apply(@PathVariable Long id,
                                                 @RequestParam(defaultValue = "Austin") String appliedBy) {
        return service.applyApprovedRecommendation(id, appliedBy);
    }

    @PostMapping("/recommendations/{id}/rollback")
    public StrategyTuningRecommendationDto rollback(@PathVariable Long id,
                                                    @RequestParam(defaultValue = "Austin") String rolledBackBy) {
        return service.rollbackRecommendation(id, rolledBackBy);
    }

    @GetMapping("/summary")
    public StrategyTuningSummaryDto summary() { return service.getTuningSummary(); }

    @GetMapping("/evaluation/{id}")
    public TuningEvaluationDetailDto evaluation(@PathVariable Long id) {
        return evaluationQueryService.getDetail(id);
    }

    @PostMapping("/evaluation/{id}/evaluate")
    public TuningEvaluationResultDto evaluate(@PathVariable Long id) {
        return evaluationQueryService.evaluate(id);
    }

    @GetMapping("/evaluation-summary")
    public TuningEvaluationSummaryDto evaluationSummary() {
        return evaluationQueryService.getSummary();
    }
}
