package com.booking.availability;

import com.booking.court.CourtResponse;
import com.booking.pricing.PricingModels.PriceType;
import com.booking.venue.ScheduleModels.*;
import com.booking.venue.ScheduleModels.Period;
import java.time.*;
import java.util.*;

public final class AvailabilityModels {
    private AvailabilityModels() { }
    public enum SlotStatus { AVAILABLE, CLOSED, PAST, HOLDING, BOOKED, MAINTENANCE }
    public record VenueResponse(String name, String timezone, int slotMinutes, int advanceBookingDays,
                                LocalDate earliestDate, LocalDate latestDate, OffsetDateTime serverTime,
                                boolean bookingEnabled, long scheduleVersion, long pricingVersion) { }
    public record Slot(UUID courtId, OffsetDateTime startAt, OffsetDateTime endAt, SlotStatus status,
                       Long price, PriceType priceType) { }
    public record AvailabilityResponse(LocalDate date, String timezone, OffsetDateTime serverTime,
                                       int slotMinutes, String currency, boolean bookingEnabled, String inventoryMode,
                                       DayType dayType, String scheduleSource, List<Period> operatingPeriods,
                                       long scheduleVersion, long pricingVersion, Instant updatedAt,
                                       List<CourtResponse> courts, List<Slot> slots) { }
}
