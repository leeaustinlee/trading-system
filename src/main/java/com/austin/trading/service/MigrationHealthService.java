package com.austin.trading.service;

import com.austin.trading.dto.response.MigrationHealthItemResponse;
import com.austin.trading.dto.response.MigrationHealthResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class MigrationHealthService {

    private final JdbcTemplate jdbcTemplate;
    private final boolean flywayEnabled;

    public MigrationHealthService(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.flyway.enabled:false}") boolean flywayEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.flywayEnabled = flywayEnabled;
    }

    public MigrationHealthResponse check() {
        List<MigrationHealthItemResponse> checks = new ArrayList<>();

        // ── 核心表格存在確認（Flyway 啟用與否都要查）──
        checks.add(build("table.position",           tableExists("position"),           "position table"));
        checks.add(build("table.ai_research_log",    tableExists("ai_research_log"),    "ai_research_log table"));
        checks.add(build("table.external_probe_log", tableExists("external_probe_log"), "external_probe_log table"));

        // ── V4 欄位確認（close_price / realized_pnl）──
        checks.add(build("column.position.close_price",
                columnExists("position", "close_price"), "position.close_price (V4)"));
        checks.add(build("column.position.realized_pnl",
                columnExists("position", "realized_pnl"), "position.realized_pnl (V4)"));

        // ── Flyway 版本紀錄（只在 Flyway 啟用時查詢）──
        if (flywayEnabled) {
            checks.add(build("flyway.version.4", flywayVersionExists("4"), "flyway V4 applied"));
            checks.add(build("flyway.version.5", flywayVersionExists("5"), "flyway V5 applied"));
            checks.add(build("flyway.version.6", flywayVersionExists("6"), "flyway V6 applied"));
        } else {
            checks.add(new MigrationHealthItemResponse(
                    "flyway.disabled", true, "Flyway 未啟用（ddl-auto:update 模式）"));
        }

        boolean ok = checks.stream().allMatch(MigrationHealthItemResponse::ok);
        return new MigrationHealthResponse(LocalDateTime.now(), ok, checks);
    }

    private MigrationHealthItemResponse build(String key, boolean ok, String detail) {
        return new MigrationHealthItemResponse(key, ok, ok ? "OK: " + detail : "MISSING: " + detail);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    private boolean columnExists(String tableName, String columnName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                Integer.class,
                tableName,
                columnName
        );
        return count != null && count > 0;
    }

    private boolean flywayVersionExists(String version) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM flyway_schema_history WHERE version = ? AND success = 1",
                    Integer.class,
                    version
            );
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}
