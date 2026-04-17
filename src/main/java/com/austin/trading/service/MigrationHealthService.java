package com.austin.trading.service;

import com.austin.trading.dto.response.MigrationHealthItemResponse;
import com.austin.trading.dto.response.MigrationHealthResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class MigrationHealthService {

    private final JdbcTemplate jdbcTemplate;

    public MigrationHealthService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public MigrationHealthResponse check() {
        List<MigrationHealthItemResponse> checks = new ArrayList<>();

        checks.add(build("table.position", tableExists("position"), "position table"));
        checks.add(build("table.ai_research_log", tableExists("ai_research_log"), "ai_research_log table"));
        checks.add(build("table.external_probe_log", tableExists("external_probe_log"), "external_probe_log table"));

        checks.add(build("column.position.close_price",
                columnExists("position", "close_price"), "position.close_price"));
        checks.add(build("column.position.realized_pnl",
                columnExists("position", "realized_pnl"), "position.realized_pnl"));

        checks.add(build("flyway.version.4", flywayVersionExists("4"), "flyway V4"));
        checks.add(build("flyway.version.5", flywayVersionExists("5"), "flyway V5"));
        checks.add(build("flyway.version.6", flywayVersionExists("6"), "flyway V6"));

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
