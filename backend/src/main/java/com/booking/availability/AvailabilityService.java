package com.booking.availability;

import static com.booking.availability.AvailabilityModels.*;
import static com.booking.venue.ScheduleModels.*;
import static com.booking.venue.ScheduleService.bad;
import com.booking.court.*;
import com.booking.pricing.*;
import com.booking.venue.*;
import com.booking.booking.InventoryRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class AvailabilityService {
    private final ScheduleRepository schedules;
    private final ScheduleService scheduleService;
    private final PricingService pricing;
    private final PricingEngine engine;
    private final CourtRepository courts;
    private final Clock clock;
    private final InventoryRepository inventory;
    public AvailabilityService(ScheduleRepository schedules, ScheduleService scheduleService,
            PricingService pricing, PricingEngine engine, CourtRepository courts, Clock clock, InventoryRepository inventory) {
        this.schedules = schedules; this.scheduleService = scheduleService; this.pricing = pricing;
        this.engine = engine; this.courts = courts; this.clock = clock;
        this.inventory = inventory;
    }

    public VenueResponse venue() {
        Settings settings = schedules.settings(false);
        OffsetDateTime now = clock.instant().atZone(ZONE).toOffsetDateTime();
        return new VenueResponse(settings.name(), ZONE.getId(), 60, settings.advanceBookingDays(), now.toLocalDate(),
                now.toLocalDate().plusDays(settings.advanceBookingDays()), now, true, settings.version(), pricing.current().version());
    }

    public AvailabilityResponse get(LocalDate date, UUID courtId) {
        OffsetDateTime now = clock.instant().atZone(ZONE).toOffsetDateTime();
        Settings settings = schedules.settings(false);
        if (date.isBefore(now.toLocalDate().minusDays(30)) || date.isAfter(now.toLocalDate().plusDays(settings.advanceBookingDays())))
            throw bad("DATE_OUT_OF_RANGE", "僅可查詢過去 30 天至可提前預約日數內的日期");
        List<Court> active = courtId == null ? courts.findByStatusOrderByDisplayOrderAscNameAsc(CourtStatus.ACTIVE)
                : List.of(courts.findByIdAndStatus(courtId, CourtStatus.ACTIVE).orElseThrow(CourtNotFoundException::new));
        ResolvedDay day = scheduleService.resolve(date);
        var prices = pricing.current();
        List<Slot> slots = new ArrayList<>();
        Map<String, InventoryRepository.Claim> claims = new HashMap<>();
        inventory.active(now.toInstant()).forEach(claim -> claims.put(claim.courtId()+"/"+claim.startAt().toInstant(),claim));
        for (Court court : active) for (int hour = 0; hour < 24; hour++) {
            int slotHour = hour;
            OffsetDateTime start = date.atStartOfDay(ZONE).plusHours(hour).toOffsetDateTime();
            boolean open = day.periods().stream().anyMatch(period -> period.startHour() <= slotHour && slotHour < period.endHour());
            SlotStatus status = !open ? SlotStatus.CLOSED : !start.isAfter(now) ? SlotStatus.PAST : SlotStatus.AVAILABLE;
            var claim = claims.get(court.getId()+"/"+start.toInstant());
            if (status == SlotStatus.AVAILABLE && claim != null)
                status = claim.blockId() != null ? SlotStatus.MAINTENANCE : claim.holdId() != null ? SlotStatus.HOLDING : SlotStatus.BOOKED;
            var quote = open ? engine.quote(prices.rules(), court.getId(), day.dayType(), hour) : null;
            slots.add(new Slot(court.getId(), start, start.plusHours(1), status,
                    quote == null ? null : quote.price(), quote == null ? null : quote.priceType()));
        }
        // Public responses expose occupied states without owner identities.
        Instant updated = settings.updatedAt().isAfter(prices.updatedAt()) ? settings.updatedAt() : prices.updatedAt();
        for (Court court : active) if (court.getUpdatedAt().toInstant().isAfter(updated)) updated = court.getUpdatedAt().toInstant();
        return new AvailabilityResponse(date, ZONE.getId(), now, 60, "TWD", true, "LIVE", day.dayType(),
                day.source(), day.periods(), settings.version(), prices.version(), updated,
                active.stream().map(CourtResponse::from).toList(), List.copyOf(slots));
    }
}
