package com.booking.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class BookingModels {
    private BookingModels() { }
    public enum HoldStatus { ACTIVE, CONSUMED, RELEASED, EXPIRED }
    public enum OrderStatus { PENDING_PAYMENT, CONFIRMED, CANCELLED, EXPIRED }
    public enum PaymentStatus { UNPAID, PAID }
    public enum PaymentMethod { CREDIT_CARD, CASH, ONSITE_CARD }
    public enum Source { MEMBER, WALK_IN, PHONE }
    public enum RefundStatus { NOT_REQUIRED, PENDING_REVIEW }
    public record SlotRequest(@NotNull UUID courtId, @NotNull OffsetDateTime startAt) { }
    public record HoldRequest(@NotNull @Size(min=1, max=12) List<@NotNull @Valid SlotRequest> items) { }
    public record Customer(@NotBlank @Size(max=100) String name,
                           @NotBlank @Pattern(regexp="^(?=.*[0-9])[0-9+()\\- ]{6,20}$") String phone) { }
    public record OrderRequest(@NotNull UUID holdId, @NotNull @Valid Customer customer,
                               @NotNull PaymentMethod paymentMethod) { }
    public record CancellationRequest(@NotBlank @Size(max=200) String reason) { }
    public record Item(UUID id, UUID courtId, OffsetDateTime startAt, OffsetDateTime endAt, long unitPrice) { }
    public record HoldResponse(UUID id, HoldStatus status, OffsetDateTime expiresAt, String currency,
                               long totalAmount, long pricingVersion, UUID orderId, List<Item> items) { }
    public record OrderResponse(UUID id, String orderNumber, UUID memberId, Source source,
                                OrderStatus status, PaymentStatus paymentStatus, PaymentMethod paymentMethod,
                                RefundStatus refundStatus, Customer customer, String notes,
                                String currency, long totalAmount, long pricingVersion,
                                OffsetDateTime expiresAt, OffsetDateTime createdAt, OffsetDateTime cancelledAt,
                                String cancellationReason, OffsetDateTime checkedInAt, long version, List<Item> items) { }
    public record OrderPage(List<OrderResponse> content, int page, int size, long totalElements, long totalPages) { }
    public record PricedSlots(long pricingVersion, List<Item> items, long totalAmount) { }
}
