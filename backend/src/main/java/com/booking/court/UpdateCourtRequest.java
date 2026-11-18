package com.booking.court;

import jakarta.validation.constraints.*;

public record UpdateCourtRequest(@Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*") String name,
        @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*") String type,
        CourtStatus status, @Min(0) Integer displayOrder, @NotNull @Min(0) Long expectedVersion) { }
