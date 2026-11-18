package com.booking.venue;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.List;
import java.util.UUID;

public final class ScheduleModels {
    private ScheduleModels() { }
    public static final ZoneId ZONE = ZoneId.of("Asia/Taipei");
    public enum DayType { WEEKDAY, WEEKEND, HOLIDAY }
    public record Period(@NotNull @Min(0) @Max(23) Integer startHour,
                         @NotNull @Min(1) @Max(24) Integer endHour) { }
    public record DayHours(@NotNull @Min(1) @Max(7) Integer dayOfWeek,
                           @NotNull @Size(max = 24) List<@NotNull @Valid Period> periods) { }
    public record HoursRequest(@NotNull @Min(0) Long expectedVersion,
                               @NotNull @Min(1) @Max(365) Integer advanceBookingDays,
                               @NotNull @Size(min = 7, max = 7) List<@NotNull @Valid DayHours> days) { }
    public record HoursResponse(long version, String timezone, int slotMinutes, int advanceBookingDays,
                                Instant updatedAt, List<DayHours> days) { }
    public record ExceptionRequest(@NotNull LocalDate date, @NotBlank @Size(max = 100) String label,
                                   @NotNull DayType dayType,
                                   @NotNull @Size(max = 24) List<@NotNull @Valid Period> periods) { }
    // PATCH replaces the specified day's entire definition, including its intervals.
    public record ExceptionUpdateRequest(@NotNull @Min(0) Long expectedVersion,
                                         @NotNull LocalDate date, @NotBlank @Size(max = 100) String label,
                                         @NotNull DayType dayType,
                                         @NotNull @Size(max = 24) List<@NotNull @Valid Period> periods) { }
    public record CalendarException(UUID id, LocalDate date, String label, DayType dayType,
                                    long version, Instant updatedAt, List<Period> periods) { }
    public record Settings(String name, int advanceBookingDays, long version, Instant updatedAt) { }
    public record ResolvedDay(DayType dayType, String source, List<Period> periods) { }
}
