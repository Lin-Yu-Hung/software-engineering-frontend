package com.booking.court;

import java.util.UUID;

public record CourtResponse(UUID id, String name, String type, boolean enabled) {
    public static CourtResponse from(Court court) {
        return new CourtResponse(court.getId(), court.getName(), court.getType(),
                court.getStatus() == CourtStatus.ACTIVE);
    }
}
