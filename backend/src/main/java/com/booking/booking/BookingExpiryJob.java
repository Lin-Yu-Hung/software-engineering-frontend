package com.booking.booking;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name="app.booking.expiry-enabled",havingValue="true",matchIfMissing=true)
public class BookingExpiryJob {
    private final BookingService bookings;
    public BookingExpiryJob(BookingService bookings) { this.bookings=bookings; }
    @Scheduled(fixedDelayString="${app.booking.expiry-scan-ms:30000}")
    public void expire() { bookings.expire(); }
}
