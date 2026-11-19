package com.booking.common;

import java.time.Instant;
import java.util.List;

public record ValidationErrorResponse(String code, String message, String path, Instant timestamp,
                                      List<FieldViolation> errors) {
    public record FieldViolation(String field, String message) { }
}
