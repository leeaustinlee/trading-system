package com.austin.trading.controller;

import com.austin.trading.dto.response.LifecycleExitReviewResponse;
import com.austin.trading.service.LifecycleExitReviewService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/theme-lifecycle/exit-review")
public class LifecycleExitReviewController {
    private final LifecycleExitReviewService service;

    public LifecycleExitReviewController(LifecycleExitReviewService service) {
        this.service = service;
    }

    @GetMapping
    public LifecycleExitReviewResponse report(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.report(date);
    }

    @PostMapping("/rebuild")
    public LifecycleExitReviewResponse rebuild(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.rebuild(date);
    }
}
