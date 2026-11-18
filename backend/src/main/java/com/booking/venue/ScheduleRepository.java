package com.booking.venue;

import static com.booking.venue.ScheduleModels.*;
import com.booking.venue.ScheduleModels.Period;
import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ScheduleRepository {
    private final JdbcTemplate jdbc;
    public ScheduleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Settings settings(boolean lock) {
        return jdbc.queryForObject("SELECT * FROM venue_settings WHERE id = 1" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Settings(rs.getString("name"), rs.getInt("advance_booking_days"),
                        rs.getLong("version"), rs.getTimestamp("updated_at").toInstant()));
    }

    public List<DayHours> days() {
        Map<Integer, List<Period>> periods = new HashMap<>();
        jdbc.query("SELECT * FROM operating_hours ORDER BY day_of_week, start_hour", rs -> {
            periods.computeIfAbsent(rs.getInt("day_of_week"), ignored -> new ArrayList<>())
                    .add(new Period(rs.getInt("start_hour"), rs.getInt("end_hour")));
        });
        return IntStream.rangeClosed(1, 7).mapToObj(day -> new DayHours(day,
                List.copyOf(periods.getOrDefault(day, List.of())))).toList();
    }

    public void replaceHours(HoursRequest request, Instant now) {
        jdbc.update("DELETE FROM operating_hours");
        for (DayHours day : request.days()) for (Period period : day.periods())
            jdbc.update("INSERT INTO operating_hours VALUES (?,?,?)", day.dayOfWeek(), period.startHour(), period.endHour());
        jdbc.update("UPDATE venue_settings SET advance_booking_days = ?, version = version + 1, updated_at = ? WHERE id = 1",
                request.advanceBookingDays(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    public void changed(Instant now) {
        jdbc.update("UPDATE venue_settings SET version = version + 1, updated_at = ? WHERE id = 1",
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }

    public List<CalendarException> exceptions(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM calendar_exceptions WHERE date BETWEEN ? AND ? ORDER BY date", (rs, row) -> {
            UUID id = rs.getObject("id", UUID.class);
            return new CalendarException(id, rs.getObject("date", LocalDate.class), rs.getString("label"),
                    DayType.valueOf(rs.getString("day_type")), rs.getLong("version"),
                    rs.getTimestamp("updated_at").toInstant(), exceptionPeriods(id));
        }, from, to);
    }

    public Optional<CalendarException> find(UUID id) {
        return jdbc.query("SELECT * FROM calendar_exceptions WHERE id = ?", (rs, row) ->
                new CalendarException(id, rs.getObject("date", LocalDate.class), rs.getString("label"),
                        DayType.valueOf(rs.getString("day_type")), rs.getLong("version"),
                        rs.getTimestamp("updated_at").toInstant(), exceptionPeriods(id)), id).stream().findFirst();
    }

    private List<Period> exceptionPeriods(UUID id) {
        return jdbc.query("SELECT * FROM calendar_exception_hours WHERE exception_id = ? ORDER BY start_hour",
                (rs, row) -> new Period(rs.getInt("start_hour"), rs.getInt("end_hour")), id);
    }

    public void insert(UUID id, ExceptionRequest request, Instant now) {
        jdbc.update("INSERT INTO calendar_exceptions(id,date,label,day_type,updated_at) VALUES (?,?,?,?,?)",
                id, request.date(), request.label().strip(), request.dayType().name(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        insertPeriods(id, request.periods());
    }

    public void update(UUID id, ExceptionUpdateRequest request, Instant now) {
        jdbc.update("UPDATE calendar_exceptions SET date=?, label=?, day_type=?, version=version+1, updated_at=? WHERE id=?",
                request.date(), request.label().strip(), request.dayType().name(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC), id);
        jdbc.update("DELETE FROM calendar_exception_hours WHERE exception_id = ?", id);
        insertPeriods(id, request.periods());
    }

    private void insertPeriods(UUID id, List<Period> periods) {
        for (Period p : periods) jdbc.update("INSERT INTO calendar_exception_hours VALUES (?,?,?)", id, p.startHour(), p.endHour());
    }
    public void delete(UUID id) { jdbc.update("DELETE FROM calendar_exceptions WHERE id = ?", id); }
}
