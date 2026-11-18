package com.booking.backoffice;

import static com.booking.backoffice.BackofficeModels.*;
import static com.booking.booking.BookingModels.*;
import static com.booking.booking.BookingService.*;
import static com.booking.booking.InventoryRepository.conflict;
import com.booking.availability.AvailabilityService;
import com.booking.booking.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BackofficeService {
    private final BookingService booking;
    private final BookingRepository orders;
    private final BackofficeRepository repository;
    private final AvailabilityService availability;
    public BackofficeService(BookingService booking,BookingRepository orders,BackofficeRepository repository,AvailabilityService availability) {
        this.booking=booking;this.orders=orders;this.repository=repository;this.availability=availability;
    }
    public OrderResponse create(UUID actor,String key,StaffOrderRequest request) {
        booking.lockAndExpire();booking.validateKey(key);String hash=booking.hash(request);
        var replay=orders.replay(actor,"STAFF_ORDER",key,hash);
        if(replay.isPresent()) return find(replay.get());
        if(request.source()==Source.MEMBER) throw bad("INVALID_SOURCE","櫃台來源限 WALK_IN 或 PHONE");
        if(request.paymentMethod()==PaymentMethod.CREDIT_CARD) throw bad("INVALID_PAYMENT_METHOD","櫃台付款方式限 CASH 或 ONSITE_CARD");
        var priced=booking.priceAvailable(request.items());UUID id=UUID.randomUUID();
        orders.insertOrder(id,booking.number(id),null,actor,null,request.source(),OrderStatus.CONFIRMED,request.paymentMethod(),request.customer(),request.notes().strip(),priced,null);
        orders.remember(actor,"STAFF_ORDER",key,hash,id);return find(id);
    }
    public OrderResponse order(UUID id) { booking.lockAndExpire();return find(id); }
    private OrderResponse find(UUID id) { return orders.order(id).orElseThrow(()->missing("ORDER_NOT_FOUND","找不到訂單")); }
    private void version(OrderResponse order,long expected) {
        if(order.version()!=expected) throw conflict("VERSION_CONFLICT","訂單已更新，請重新取得最新版本");
    }
    public OrderPage list(LocalDate date,int page,int size) { booking.lockAndExpire();return repository.list(date,page,size); }
    public TimetableResponse timetable(LocalDate date) {
        booking.lockAndExpire();var grid=availability.get(date,null);
        return new TimetableResponse(grid,repository.timetableOrders(date),repository.blocks(date));
    }
    public OrderResponse edit(UUID id,OrderEdit request) {
        booking.lockAndExpire();var order=find(id);version(order,request.expectedVersion());
        if(order.status()==OrderStatus.CANCELLED || order.status()==OrderStatus.EXPIRED) throw conflict("ORDER_NOT_EDITABLE","已取消或到期的訂單不可修改");
        repository.edit(id,request);return find(id);
    }
    public OrderResponse cancel(UUID id,CancellationRequest request) { booking.lockAndExpire();return booking.cancelOrder(find(id),request.reason()); }
    public OrderResponse checkIn(UUID id,UUID actor,VersionRequest request) {
        booking.lockAndExpire();var order=find(id);
        // A successful retry with its original version is harmless.
        if(order.checkedInAt()!=null) return order;
        version(order,request.expectedVersion());
        if(order.status()!=OrderStatus.CONFIRMED) throw conflict("ORDER_NOT_CONFIRMED","只有已確認訂單可報到");
        var first=order.items().stream().map(Item::startAt).min(Comparator.naturalOrder()).orElseThrow();
        var last=order.items().stream().map(Item::endAt).max(Comparator.naturalOrder()).orElseThrow();
        var now=booking.now();
        if(now.isBefore(first.minusHours(24)) || !now.isBefore(last)) throw conflict("CHECK_IN_WINDOW","僅可在預約前 24 小時至最後時段結束前報到");
        repository.checkIn(id,actor,now);return find(id);
    }
    public OrderResponse reverseCheckIn(UUID id,VersionRequest request) {
        booking.lockAndExpire();var order=find(id);
        if(order.checkedInAt()==null) return order;
        version(order,request.expectedVersion());repository.reverseCheckIn(id);return find(id);
    }
    public BlockResponse createBlock(UUID actor,String key,BlockRequest request) {
        booking.lockAndExpire();booking.validateKey(key);String hash=booking.hash(request);
        var replay=orders.replay(actor,"BLOCK",key,hash);
        if(replay.isPresent()) return findBlock(replay.get());
        var slots=booking.priceAvailable(request.items());UUID id=UUID.randomUUID();
        repository.block(id,actor,request,slots.items());orders.remember(actor,"BLOCK",key,hash,id);return findBlock(id);
    }
    private BlockResponse findBlock(UUID id) { return repository.block(id).orElseThrow(()->missing("BLOCK_NOT_FOUND","找不到封場紀錄")); }
    public List<BlockResponse> blocks(LocalDate date) { booking.lockAndExpire();return repository.blocks(date); }
    public void releaseBlock(UUID id) { booking.lockAndExpire();findBlock(id);repository.releaseBlock(id,booking.now()); }
}
