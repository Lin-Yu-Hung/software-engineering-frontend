package com.booking.court;

public class CourtNotFoundException extends RuntimeException {
    public CourtNotFoundException() {
        super("找不到開放中的場地");
    }
}
