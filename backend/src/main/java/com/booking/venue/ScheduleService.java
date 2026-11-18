package com.booking.venue;

import static com.booking.venue.ScheduleModels.*;
import com.booking.venue.ScheduleModels.Period;
import com.booking.common.BusinessException;
import com.booking.booking.InventoryRepository;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ScheduleService {
    private final ScheduleRepository repository;
    private final Clock clock;
    private final InventoryRepository inventory;
    public ScheduleService(ScheduleRepository repository, Clock clock, InventoryRepository inventory) { this.repository = repository; this.clock = clock; this.inventory = inventory; }

    public HoursResponse hours() {
        Settings settings = repository.settings(false);
        return new HoursResponse(settings.version(), ZONE.getId(), 60, settings.advanceBookingDays(),
                settings.updatedAt(), repository.days());
    }

    @Transactional
    public HoursResponse replace(HoursRequest request) {
        if (request.days().stream().map(DayHours::dayOfWeek).distinct().count() != 7)
            throw bad("INVALID_WEEK", "每週必須包含星期一到星期日，且不可重複");
        request.days().forEach(day -> validatePeriods(day.periods()));
        checkVersion(repository.settings(true).version(), request.expectedVersion());
        repository.replaceHours(request, clock.instant());
        inventory.assertScheduled(this::resolve, clock.instant());
        return hours();
    }

    public List<CalendarException> list(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 365)
            throw bad("INVALID_DATE_RANGE", "日期範圍需依序排列，且最多 366 天");
        return repository.exceptions(from, to);
    }

    @Transactional
    public CalendarException create(ExceptionRequest request) {
        validateDate(request.date());
        validatePeriods(request.periods());
        repository.settings(true);
        UUID id = UUID.randomUUID();
        repository.insert(id, request, clock.instant());
        inventory.assertScheduled(this::resolve, clock.instant());
        repository.changed(clock.instant());
        return repository.find(id).orElseThrow();
    }

    @Transactional
    public CalendarException update(UUID id, ExceptionUpdateRequest request) {
        validateDate(request.date());
        validatePeriods(request.periods());
        repository.settings(true);
        CalendarException current = find(id);
        checkVersion(current.version(), request.expectedVersion());
        validateDate(current.date());
        repository.update(id, request, clock.instant());
        inventory.assertScheduled(this::resolve, clock.instant());
        repository.changed(clock.instant());
        return find(id);
    }

    @Transactional
    public void delete(UUID id, long expectedVersion) {
        repository.settings(true);
        checkVersion(find(id).version(), expectedVersion);
        repository.delete(id);
        inventory.assertScheduled(this::resolve, clock.instant());
        repository.changed(clock.instant());
    }

    private CalendarException find(UUID id) {
        return repository.find(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND,
                "CALENDAR_EXCEPTION_NOT_FOUND", "找不到例外日設定"));
    }

    public ResolvedDay resolve(LocalDate date) {
        var exceptions = repository.exceptions(date, date);
        if (!exceptions.isEmpty()) {
            var exception = exceptions.getFirst();
            return new ResolvedDay(exception.dayType(), "EXCEPTION", exception.periods());
        }
        DayType type = date.getDayOfWeek().getValue() >= 6 ? DayType.WEEKEND : DayType.WEEKDAY;
        return new ResolvedDay(type, "WEEKLY", repository.days().get(date.getDayOfWeek().getValue() - 1).periods());
    }

    private void validateDate(LocalDate date) {
        if (date.isBefore(LocalDate.now(clock.withZone(ZONE)))) throw bad("PAST_DATE", "不能新增或修改過去的營業日期");
    }

    public static void validatePeriods(List<Period> periods) {
        int end = -1;
        for (Period period : periods.stream().sorted(Comparator.comparing(Period::startHour)).toList()) {
            if (period.startHour() >= period.endHour() || period.startHour() < end)
                throw bad("INVALID_PERIODS", "營業區間須為整點、開始早於結束，且不可重疊");
            end = period.endHour();
        }
    }

    public static void checkVersion(long actual, long expected) {
        if (actual != expected) throw new BusinessException(HttpStatus.CONFLICT, "STALE_VERSION", "資料已被修改，請重新讀取再操作");
    }
    public static BusinessException bad(String code, String message) { return new BusinessException(HttpStatus.BAD_REQUEST, code, message); }
}
