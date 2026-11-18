package com.booking.court;

import jakarta.validation.constraints.*;

public record CreateCourtRequest(@NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String type, @NotNull CourtStatus status,
        @NotNull @Min(0) Integer displayOrder) { }
