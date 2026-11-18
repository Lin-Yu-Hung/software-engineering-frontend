package com.booking.booking;

import static com.booking.booking.BookingModels.*;
import static com.booking.booking.InventoryRepository.conflict;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentConfirmationService {
    private final BookingService bookings;
    private final BookingRepository repository;
    private final Clock clock;
    public PaymentConfirmationService(BookingService bookings,BookingRepository repository,Clock clock) { this.bookings=bookings; this.repository=repository; this.clock=clock; }
    // Java integration only: the payment teammate must verify gateway signature and transaction first.
    @Transactional
    public OrderResponse confirmVerifiedPayment(UUID id,long amount,String reference) {
        bookings.lockAndExpire();
        var order=repository.order(id).orElseThrow(() -> BookingService.missing("ORDER_NOT_FOUND","找不到訂單"));
        if (reference==null || reference.isBlank() || reference.length()>100) throw BookingService.bad("INVALID_PAYMENT_REFERENCE","付款交易編號格式錯誤");
        if (amount!=order.totalAmount()) throw conflict("PAYMENT_AMOUNT_MISMATCH","付款金額與訂單不符");
        if (order.paymentStatus()==PaymentStatus.PAID && reference.equals(repository.paidReference(id))) return order;
        if (order.status()!=OrderStatus.PENDING_PAYMENT && !(order.status()==OrderStatus.CONFIRMED && order.paymentStatus()==PaymentStatus.UNPAID))
            throw conflict("ORDER_NOT_PAYABLE","此訂單不能接受付款確認");
        if (order.items().stream().anyMatch(item -> !item.startAt().toInstant().isAfter(clock.instant()))) throw conflict("BOOKING_ALREADY_STARTED","預約已開始");
        repository.paid(id,reference); return repository.order(id).orElseThrow();
    }
}
