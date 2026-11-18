package com.booking.pricing;

import static com.booking.pricing.PricingModels.*;
import com.booking.venue.ScheduleModels.DayType;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PricingRepository {
    private final JdbcTemplate jdbc;
    public PricingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public long version(boolean lock) {
        return jdbc.queryForObject("SELECT version FROM pricing_config WHERE id=1" + (lock ? " FOR UPDATE" : ""), Long.class);
    }
    public PricingResponse current() {
        long version = version(false);
        Instant updatedAt = jdbc.queryForObject("SELECT created_at FROM pricing_versions WHERE version=?",
                (rs, row) -> rs.getTimestamp(1).toInstant(), version);
        List<Rule> rules = jdbc.query("SELECT * FROM pricing_rules WHERE pricing_version=? ORDER BY court_id NULLS FIRST, day_type, start_hour, priority",
                (rs, row) -> new Rule(rs.getObject("court_id", UUID.class), DayType.valueOf(rs.getString("day_type")),
                        rs.getInt("start_hour"), rs.getInt("end_hour"), rs.getLong("price"),
                        PriceType.valueOf(rs.getString("price_type")), rs.getInt("priority")), version);
        return new PricingResponse(version, "TWD", updatedAt, rules);
    }
    public void publish(long version, List<Rule> rules, Instant now) {
        jdbc.update("INSERT INTO pricing_versions(version,created_at) VALUES (?,?)", version, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
        for (Rule rule : rules) jdbc.update("INSERT INTO pricing_rules VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), version, rule.courtId(), rule.dayType().name(), rule.startHour(), rule.endHour(),
                rule.price(), rule.priceType().name(), rule.priority());
        jdbc.update("UPDATE pricing_config SET version=? WHERE id=1", version);
    }
    public Set<UUID> knownCourts() {
        return new HashSet<>(jdbc.query("SELECT id FROM courts", (rs, row) -> rs.getObject(1, UUID.class)));
    }
}
