package com.booking.health;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "系統健康檢查", description = "確認 API、PostgreSQL 與資料庫結構版本是否正常")
public class DatabaseHealthController {
    private final JdbcTemplate jdbc;

    public DatabaseHealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/health")
    @Operation(summary = "檢查 API 與資料庫狀態",
            description = "實際查詢 PostgreSQL、資料庫遷移版本及場地數量；資料庫無法查詢時回傳 503。")
    public ResponseEntity<DatabaseHealthResponse> health() {
        try {
            String databaseName = jdbc.queryForObject("SELECT current_database()", String.class);
            Long count = jdbc.queryForObject("SELECT count(*) FROM courts", Long.class);
            String version = jdbc.queryForObject("""
                    SELECT version FROM flyway_schema_history
                    WHERE success AND version IS NOT NULL
                    ORDER BY installed_rank DESC LIMIT 1
                    """, String.class);
            return ResponseEntity.ok(new DatabaseHealthResponse(
                    "UP", "UP", databaseName, version, count, Instant.now()));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new DatabaseHealthResponse("DOWN", "DOWN", null, null, null, Instant.now()));
        }
    }
}
