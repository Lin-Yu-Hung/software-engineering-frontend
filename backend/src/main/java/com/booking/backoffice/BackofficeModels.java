package com.booking.backoffice;

import com.booking.availability.AvailabilityModels.AvailabilityResponse;
import com.booking.booking.BookingModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class BackofficeModels {
    private BackofficeModels() { }
    public record StaffOrderRequest(@NotNull Source source, @NotNull PaymentMethod paymentMethod,
            @NotNull @Valid Customer customer, @NotNull @Size(max=500) String notes,
            @NotNull @Size(min=1,max=12) List<@NotNull @Valid SlotRequest> items) { }
    public record OrderEdit(@NotNull @Min(0) Long expectedVersion,
            @NotNull @Valid Customer customer,@NotNull @Size(max=500) String notes) { }
    public record VersionRequest(@NotNull @Min(0) Long expectedVersion) { }
    public record BlockRequest(@NotBlank @Size(max=200) String reason,
            @NotNull @Size(min=1,max=12) List<@NotNull @Valid SlotRequest> items) { }
    public record BlockSlot(UUID courtId,OffsetDateTime startAt,OffsetDateTime endAt) { }
    public record BlockResponse(UUID id,String reason,boolean active,OffsetDateTime createdAt,
            OffsetDateTime releasedAt,List<BlockSlot> items) { }
    public record TimetableResponse(AvailabilityResponse grid,List<OrderResponse> orders,List<BlockResponse> blocks) { }
}
