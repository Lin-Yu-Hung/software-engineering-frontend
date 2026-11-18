package com.booking;

import com.booking.availability.*;
import com.booking.booking.InventoryRepository;
import com.booking.availability.AvailabilityModels.*;
import com.booking.common.BusinessException;
import com.booking.court.*;
import com.booking.pricing.*;
import com.booking.pricing.PricingModels.*;
import com.booking.venue.*;
import com.booking.venue.ScheduleModels.*;
import com.booking.venue.ScheduleModels.Period;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PricingAndAvailabilityTests {
    private final PricingEngine engine = new PricingEngine();
    private final UUID court = UUID.randomUUID();
    private List<Rule> defaults() {
        return new ArrayList<>(Arrays.stream(DayType.values()).map(type ->
                new Rule(null, type, 0, 24, 350L, PriceType.OFF_PEAK, 0)).toList());
    }

    @Test void peakBoundaryIsStartInclusiveEndExclusive() {
        var rules = defaults();
        rules.add(new Rule(null, DayType.WEEKDAY, 18, 24, 500L, PriceType.PEAK, 10));
        engine.validate(rules);
        assertThat(engine.quote(rules, court, DayType.WEEKDAY, 17).price()).isEqualTo(350);
        assertThat(engine.quote(rules, court, DayType.WEEKDAY, 18).price()).isEqualTo(500);
        assertThat(engine.quote(rules, court, DayType.WEEKDAY, 23).price()).isEqualTo(500);
    }

    @Test void courtScopeWinsBeforePriorityAndHolidayClassificationWinsOverWeekday() {
        var rules = defaults();
        rules.add(new Rule(null, DayType.HOLIDAY, 0, 24, 600L, PriceType.HOLIDAY, 100));
        rules.add(new Rule(court, DayType.HOLIDAY, 0, 24, 450L, PriceType.HOLIDAY, 0));
        rules.add(new Rule(court, DayType.HOLIDAY, 12, 14, 1L, PriceType.SPECIAL, 1));
        engine.validate(rules);
        assertThat(engine.quote(rules, court, DayType.HOLIDAY, 11).price()).isEqualTo(450);
        assertThat(engine.quote(rules, court, DayType.HOLIDAY, 12).price()).isEqualTo(1);
        assertThat(engine.quote(rules, court, DayType.HOLIDAY, 14).price()).isEqualTo(450);
        assertThat(engine.quote(rules, UUID.randomUUID(), DayType.HOLIDAY, 12).price()).isEqualTo(600);
        assertThat(engine.quote(rules, court, DayType.WEEKDAY, 12).price()).isEqualTo(350);
    }

    @Test void equalPriorityOverlapAndMissingFallbackAreRejected() {
        var rules = defaults();
        rules.add(new Rule(null, DayType.WEEKDAY, 12, 14, 500L, PriceType.SPECIAL, 0));
        assertThatThrownBy(() -> engine.validate(rules)).isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("AMBIGUOUS_PRICING");
        assertThatThrownBy(() -> engine.validate(List.of(new Rule(court, DayType.WEEKDAY, 0, 24, 500L, PriceType.PEAK, 0))))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("INCOMPLETE_PRICING");
    }

    @Test void adjacentPriceRangesDoNotOverlapAndKeepIntegerAmounts() {
        var rules = defaults();
        rules.removeFirst();
        rules.add(new Rule(null, DayType.WEEKDAY, 0, 18, 1L, PriceType.OFF_PEAK, 0));
        rules.add(new Rule(null, DayType.WEEKDAY, 18, 24, 100000L, PriceType.PEAK, 0));
        engine.validate(rules);
        assertThat(engine.quote(rules, court, DayType.WEEKDAY, 17).price()).isEqualTo(1L);
        assertThat(engine.quote(rules, court, DayType.WEEKDAY, 18).price()).isEqualTo(100000L);
    }

    @Test void taipeiDateAndExactStartBoundaryUseInjectedClock() {
        // UTC is still Sunday while Taipei is Monday at exactly 08:00.
        Clock clock = Clock.fixed(Instant.parse("2026-10-04T23:59:59Z"), ZoneOffset.UTC);
        var schedules = mock(ScheduleRepository.class);
        var scheduleService = mock(ScheduleService.class);
        var pricing = mock(PricingService.class);
        var courts = mock(CourtRepository.class);
        var inventory = mock(InventoryRepository.class);
        when(inventory.active(any())).thenReturn(List.of());
        Court demo = new Court("Boundary", "PU", CourtStatus.ACTIVE, 1, OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        when(schedules.settings(false)).thenReturn(new Settings("Test", 30, 0, Instant.EPOCH));
        when(scheduleService.resolve(any())).thenReturn(new ResolvedDay(DayType.WEEKDAY, "WEEKLY", List.of(new Period(8, 24))));
        when(pricing.current()).thenReturn(new PricingResponse(0, "TWD", Instant.EPOCH, defaults()));
        when(courts.findByStatusOrderByDisplayOrderAscNameAsc(CourtStatus.ACTIVE)).thenReturn(List.of(demo));
        var before = new AvailabilityService(schedules, scheduleService, pricing, engine, courts, clock, inventory);
        LocalDate date = LocalDate.of(2026, 10, 5);
        assertThat(before.venue().earliestDate()).isEqualTo(date);
        assertThat(before.get(date, null).slots().get(8).status()).isEqualTo(SlotStatus.AVAILABLE);
        var exact = new AvailabilityService(schedules, scheduleService, pricing, engine, courts,
                Clock.offset(clock, Duration.ofSeconds(1)), inventory);
        var response = exact.get(date, null);
        assertThat(response.slots()).hasSize(24);
        assertThat(response.slots().get(7).status()).isEqualTo(SlotStatus.CLOSED);
        assertThat(response.slots().get(8).status()).isEqualTo(SlotStatus.PAST);
        assertThat(response.slots().get(9).status()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(response.slots().get(23).endAt()).isEqualTo(OffsetDateTime.parse("2026-10-06T00:00:00+08:00"));
        assertThat(response.bookingEnabled()).isTrue();
        assertThat(response.inventoryMode()).isEqualTo("LIVE");
    }
}
