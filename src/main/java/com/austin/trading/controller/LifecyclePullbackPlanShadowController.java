package com.austin.trading.controller;

import com.austin.trading.dto.response.LifecyclePullbackPlanShadowResponse;
import com.austin.trading.service.LifecyclePullbackPlanShadowService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only P3-E lifecycle pullback plan shadow endpoint. */
@RestController
@RequestMapping("/api/theme-lifecycle/pullback-plan")
public class LifecyclePullbackPlanShadowController {
    private final LifecyclePullbackPlanShadowService service;

    public LifecyclePullbackPlanShadowController(LifecyclePullbackPlanShadowService service) {
        this.service = service;
    }

    @GetMapping("/shadow")
    public LifecyclePullbackPlanShadowResponse shadow(@RequestParam(defaultValue = "60") int days) {
        return service.report(days);
    }
}
