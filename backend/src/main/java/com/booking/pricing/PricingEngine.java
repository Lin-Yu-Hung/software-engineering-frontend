package com.booking.pricing;

import static com.booking.pricing.PricingModels.*;
import static com.booking.venue.ScheduleService.bad;
import com.booking.venue.ScheduleModels.DayType;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PricingEngine {
    public void validate(List<Rule> rules) {
        for (int i = 0; i < rules.size(); i++) {
            Rule a = rules.get(i);
            if (a.startHour() >= a.endHour()) throw bad("INVALID_PRICE_RANGE", "價格規則的開始時間須早於結束時間");
            for (int j = i + 1; j < rules.size(); j++) {
                Rule b = rules.get(j);
                if (Objects.equals(a.courtId(), b.courtId()) && a.dayType() == b.dayType()
                        && a.priority().equals(b.priority()) && a.startHour() < b.endHour() && b.startHour() < a.endHour())
                    throw bad("AMBIGUOUS_PRICING", "同一適用場地、日期類型與優先序的價格區間不可重疊");
            }
        }
        for (DayType type : DayType.values()) for (int hour = 0; hour < 24; hour++) {
            int slotHour = hour;
            if (rules.stream().noneMatch(rule -> rule.courtId() == null && matches(rule, type, slotHour)))
                throw bad("INCOMPLETE_PRICING", "每種日期類型都需有涵蓋 00:00～24:00 的場館預設費率");
        }
    }

    public Quote quote(List<Rule> rules, UUID courtId, DayType type, int hour) {
        // Explicit court rules take precedence; priority resolves overlaps within that scope.
        Rule selected = rules.stream().filter(rule -> (rule.courtId() == null || rule.courtId().equals(courtId))
                        && matches(rule, type, hour))
                .max(Comparator.<Rule>comparingInt(rule -> rule.courtId() == null ? 0 : 1)
                        .thenComparingInt(Rule::priority)).orElseThrow(() -> new IllegalStateException("Missing configured price"));
        return new Quote(selected.price(), selected.priceType());
    }

    private boolean matches(Rule rule, DayType type, int hour) {
        return rule.dayType() == type && rule.startHour() <= hour && hour < rule.endHour();
    }
}
