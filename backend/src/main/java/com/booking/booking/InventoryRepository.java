package com.booking.booking;

import com.booking.common.BusinessException;
import com.booking.venue.ScheduleModels.*;
import com.booking.venue.ScheduleModels;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class InventoryRepository {
    private final JdbcTemplate jdbc;
    public InventoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    // A small demo venue serializes inventory writes with its settings row.
    // All callers hold this lock for their entire transaction; the PK is a second safeguard.
    public void lock() { jdbc.queryForObject("SELECT id FROM venue_settings WHERE id=1 FOR UPDATE", Integer.class); }
    public void expire(Instant now) {
        var at = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
        jdbc.update("DELETE FROM slot_claims WHERE expires_at <= ?", at);
        jdbc.update("UPDATE booking_holds SET status='EXPIRED' WHERE status='ACTIVE' AND expires_at<=?", at);
        jdbc.update("UPDATE booking_orders SET status='EXPIRED',version=version+1 WHERE status='PENDING_PAYMENT' AND expires_at<=?", at);
    }
    public record Claim(UUID courtId, OffsetDateTime startAt, UUID holdId, UUID orderId, UUID blockId) { }
    public List<Claim> active(Instant now) {
        return jdbc.query("SELECT court_id,start_at,hold_id,order_id,block_id FROM slot_claims WHERE expires_at IS NULL OR expires_at>?",
                (rs, row) -> new Claim(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),rs.getObject(5, UUID.class)), OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
    }
    public void assertFree(UUID court, Instant start) {
        if (jdbc.queryForObject("SELECT count(*) FROM slot_claims WHERE court_id=? AND start_at=?", Long.class,
                court, OffsetDateTime.ofInstant(start, ZoneOffset.UTC)) > 0)
            throw conflict("SLOT_UNAVAILABLE", "時段已被預約或保留，請重新選擇");
    }
    public void assertCourtFree(UUID court, Instant now) {
        if (active(now).stream().anyMatch(c -> c.courtId().equals(court) && c.startAt().toInstant().isAfter(now)))
            throw conflict("COURT_OCCUPIED", "場地尚有未來的有效預約或保留，不能停用或退役");
    }
    public void assertScheduled(Function<LocalDate, ResolvedDay> resolver, Instant now) {
        Map<LocalDate, ResolvedDay> days = new HashMap<>();
        for (Claim claim : active(now)) {
            if (!claim.startAt().toInstant().isAfter(now)) continue;
            var local = claim.startAt().atZoneSameInstant(ScheduleModels.ZONE);
            var day = days.computeIfAbsent(local.toLocalDate(), resolver);
            if (day.periods().stream().noneMatch(p -> p.startHour()<=local.getHour() && local.getHour()<p.endHour()))
                throw conflict("OCCUPIED_SCHEDULE", "營業調整會排除有效預約或保留，請先處理占用");
        }
    }
    public static BusinessException conflict(String code, String message) {
        return new BusinessException(HttpStatus.CONFLICT, code, message);
    }
}
