package com.booking.pricing;

import com.booking.venue.ScheduleModels.DayType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class PricingModels {
    private PricingModels() { }
    public enum PriceType { OFF_PEAK, PEAK, WEEKEND, HOLIDAY, SPECIAL }
    public record Rule(UUID courtId, @NotNull DayType dayType,
                       @NotNull @Min(0) @Max(23) Integer startHour,
                       @NotNull @Min(1) @Max(24) Integer endHour,
                       @NotNull @Min(1) @Max(100000) Long price, @NotNull PriceType priceType,
                       @NotNull @Min(0) @Max(100) Integer priority) { }
    public record PricingRequest(@NotNull @Min(0) Long expectedVersion,
                                 @NotNull @Size(min = 1, max = 200) List<@NotNull @Valid Rule> rules) { }
    public record PricingResponse(long version, String currency, Instant updatedAt, List<Rule> rules) { }
    public record Quote(long price, PriceType priceType) { }
}
