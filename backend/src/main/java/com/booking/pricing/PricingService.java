package com.booking.pricing;

import static com.booking.pricing.PricingModels.*;
import static com.booking.venue.ScheduleService.*;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PricingService {
    private final PricingRepository repository;
    private final PricingEngine engine;
    private final Clock clock;
    public PricingService(PricingRepository repository, PricingEngine engine, Clock clock) {
        this.repository = repository; this.engine = engine; this.clock = clock;
    }
    public PricingResponse current() { return repository.current(); }

    @Transactional
    public PricingResponse replace(PricingRequest request) {
        engine.validate(request.rules());
        long current = repository.version(true);
        checkVersion(current, request.expectedVersion());
        var known = repository.knownCourts();
        if (request.rules().stream().anyMatch(rule -> rule.courtId() != null && !known.contains(rule.courtId())))
            throw bad("UNKNOWN_COURT", "價格規則包含不存在的場地");
        repository.publish(Math.incrementExact(current), request.rules(), clock.instant());
        return repository.current();
    }
}
