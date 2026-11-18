package com.booking.health;

import java.time.Instant;

public record DatabaseHealthResponse(
        String status, String database, String databaseName,
        String schemaVersion, Long courtCount, Instant checkedAt) {
}
