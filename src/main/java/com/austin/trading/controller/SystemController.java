package com.austin.trading.controller;

import com.austin.trading.dto.response.ExternalProbeHistoryResponse;
import com.austin.trading.dto.response.ExternalProbeResponse;
import com.austin.trading.service.ExternalProbeService;
import com.austin.trading.dto.response.MigrationHealthResponse;
import com.austin.trading.service.MigrationHealthService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final ExternalProbeService externalProbeService;
    private final MigrationHealthService migrationHealthService;

    public SystemController(
            ExternalProbeService externalProbeService,
            MigrationHealthService migrationHealthService
    ) {
        this.externalProbeService = externalProbeService;
        this.migrationHealthService = migrationHealthService;
    }

    @GetMapping("/external/probe")
    public ExternalProbeResponse probeExternal(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate taifexDate,
            @RequestParam(defaultValue = "false") boolean liveLine,
            @RequestParam(defaultValue = "false") boolean liveClaude
    ) {
        return externalProbeService.probe(taifexDate, liveLine, liveClaude);
    }

    @GetMapping("/external/probe/history")
    public List<ExternalProbeHistoryResponse> getExternalProbeHistory(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return externalProbeService.getHistory(limit);
    }

    @GetMapping("/migration/health")
    public MigrationHealthResponse getMigrationHealth() {
        return migrationHealthService.check();
    }
}
