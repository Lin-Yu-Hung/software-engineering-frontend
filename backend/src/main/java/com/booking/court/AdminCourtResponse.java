package com.booking.court;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AdminCourtResponse(UUID id, String name, String type, CourtStatus status,
        int displayOrder, long version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    static AdminCourtResponse from(Court court) {
        return new AdminCourtResponse(court.getId(), court.getName(), court.getType(), court.getStatus(),
                court.getDisplayOrder(), court.getVersion(), court.getCreatedAt(), court.getUpdatedAt());
    }
}
