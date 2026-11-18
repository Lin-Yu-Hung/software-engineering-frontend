package com.booking.booking;

import static com.booking.booking.BookingModels.*;
import static com.booking.venue.ScheduleModels.ZONE;
import static com.booking.booking.InventoryRepository.conflict;
import com.booking.common.BusinessException;
import com.booking.court.*;
import com.booking.pricing.*;
import com.booking.venue.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class BookingService {
    private final BookingRepository repository;
    private final InventoryRepository inventory;
    private final ScheduleRepository settings;
    private final ScheduleService schedule;
    private final CourtRepository courts;
    private final PricingService pricing;
    private final PricingEngine engine;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final long holdSeconds;
    public BookingService(BookingRepository repository, InventoryRepository inventory, ScheduleRepository settings,
            ScheduleService schedule, CourtRepository courts, PricingService pricing, PricingEngine engine, Clock clock,
            ObjectMapper mapper, @Value("${app.booking.hold-seconds:600}") long holdSeconds) {
        this.repository=repository; this.inventory=inventory; this.settings=settings; this.schedule=schedule;
        this.courts=courts; this.pricing=pricing; this.engine=engine; this.clock=clock; this.mapper=mapper;
        if (holdSeconds<60 || holdSeconds>900) throw new IllegalArgumentException("Hold TTL must be 60 to 900 seconds");
        this.holdSeconds=holdSeconds;
    }
    public void lockAndExpire() { inventory.lock(); inventory.expire(clock.instant()); }
    public void expire() { lockAndExpire(); }
    public HoldResponse createHold(UUID member, String key, HoldRequest request) {
        lockAndExpire();
        String hash=hash(request); validateKey(key);
        var replay=repository.replay(member,"HOLD",key,hash);
        if (replay.isPresent()) return findHold(replay.get(),member);
        if (repository.activeHoldCount(member)>0) throw conflict("ACTIVE_HOLD_EXISTS","請先完成或釋放現有保留");
        var priced=priceAvailable(request.items());
        UUID id=UUID.randomUUID();
        repository.insertHold(id,member,priced,now().plusSeconds(holdSeconds));
        repository.remember(member,"HOLD",key,hash,id);
        return findHold(id,member);
    }
    public HoldResponse hold(UUID id, UUID member) { lockAndExpire(); return findHold(id,member); }
    public void release(UUID id, UUID member) {
        lockAndExpire(); var hold=findHold(id,member);
        if (hold.status()==HoldStatus.CONSUMED) throw conflict("HOLD_CONSUMED","保留已轉成訂單，請使用取消訂單 API");
        repository.releaseHold(id);
    }
    private HoldResponse findHold(UUID id, UUID member) {
        return repository.hold(id,member).orElseThrow(() -> missing("HOLD_NOT_FOUND","找不到保留"));
    }
    public OrderResponse createOrder(UUID member, String key, OrderRequest request) {
        lockAndExpire(); validateKey(key); String hash=hash(request);
        var replay=repository.replay(member,"ORDER",key,hash);
        if (replay.isPresent()) return ownedOrder(replay.get(),member);
        if (request.paymentMethod()!=PaymentMethod.CREDIT_CARD) throw bad("INVALID_PAYMENT_METHOD","會員訂單僅支援線上信用卡");
        var hold=findHold(request.holdId(),member);
        if (hold.status()!=HoldStatus.ACTIVE) throw conflict("HOLD_NOT_ACTIVE","保留已到期、釋放或被使用");
        UUID id=UUID.randomUUID();
        repository.insertOrder(id,number(id),member,member,hold.id(),Source.MEMBER,OrderStatus.PENDING_PAYMENT,
                request.paymentMethod(),request.customer(),"",new PricedSlots(hold.pricingVersion(),hold.items(),hold.totalAmount()),hold.expiresAt());
        repository.remember(member,"ORDER",key,hash,id);
        return ownedOrder(id,member);
    }
    public OrderResponse order(UUID id, UUID member) { lockAndExpire(); return ownedOrder(id,member); }
    public OrderPage orders(UUID member, String scope, int page, int size) {
        lockAndExpire();
        if (!List.of("all","upcoming","past").contains(scope)) throw bad("INVALID_SCOPE","scope 可用 all、upcoming、past");
        return repository.memberOrders(member,scope,page,size,now());
    }
    public OrderResponse cancel(UUID id, UUID member, CancellationRequest request) {
        lockAndExpire(); var order=ownedOrder(id,member); return cancelOrder(order,request.reason());
    }
    public OrderResponse cancelOrder(OrderResponse order, String reason) {
        if (order.status()==OrderStatus.CANCELLED) return order;
        if (order.status()==OrderStatus.EXPIRED) throw conflict("ORDER_EXPIRED","訂單已到期");
        if (order.checkedInAt()!=null) throw conflict("ORDER_CHECKED_IN","請先由櫃台撤銷報到再取消訂單");
        if (order.items().stream().anyMatch(item -> !item.startAt().toInstant().isAfter(clock.instant())))
            throw conflict("BOOKING_ALREADY_STARTED","已開始的預約不可取消");
        repository.cancel(order.id(),reason,now()); return repository.order(order.id()).orElseThrow();
    }
    public OrderResponse ownedOrder(UUID id, UUID member) {
        return repository.order(id).filter(order -> member.equals(order.memberId()))
                .orElseThrow(() -> missing("ORDER_NOT_FOUND","找不到訂單"));
    }
    public PricedSlots priceAvailable(List<SlotRequest> requests) {
        if (requests.isEmpty() || requests.size()>12) throw bad("INVALID_ITEMS","一次可預約 1 到 12 個時段");
        var now=clock.instant(); var today=now.atZone(ZONE).toLocalDate();
        var latest=today.plusDays(settings.settings(false).advanceBookingDays());
        var prices=pricing.current(); var items=new ArrayList<Item>(); var unique=new HashSet<String>();
        for (var requested:requests) {
            var local=requested.startAt().atZoneSameInstant(ZONE); var start=local.toInstant();
            if (local.getMinute()!=0 || local.getSecond()!=0 || local.getNano()!=0 || !start.isAfter(now)
                    || local.toLocalDate().isAfter(latest)) throw bad("INVALID_SLOT_TIME","時段須為未來整點且在開放日期內");
            if (!unique.add(requested.courtId()+"/"+start)) throw bad("DUPLICATE_SLOT","不可重複選擇相同時段");
            courts.findByIdAndStatus(requested.courtId(),CourtStatus.ACTIVE).orElseThrow(CourtNotFoundException::new);
            var day=schedule.resolve(local.toLocalDate());
            if (day.periods().stream().noneMatch(p -> p.startHour()<=local.getHour() && local.getHour()<p.endHour()))
                throw conflict("VENUE_CLOSED","此時段不在營業時間內");
            inventory.assertFree(requested.courtId(),start);
            var quote=engine.quote(prices.rules(),requested.courtId(),day.dayType(),local.getHour());
            var at=OffsetDateTime.ofInstant(start,ZoneOffset.UTC);
            items.add(new Item(UUID.randomUUID(),requested.courtId(),at,at.plusHours(1),quote.price()));
        }
        return new PricedSlots(prices.version(),List.copyOf(items),items.stream().mapToLong(Item::unitPrice).reduce(0,Math::addExact));
    }
    public String hash(Object request) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    public void validateKey(String key) {
        if (key==null || !key.matches("[A-Za-z0-9._:-]{8,100}")) throw bad("INVALID_IDEMPOTENCY_KEY","Idempotency-Key 需為 8～100 個英數或 . _ : - 字元");
    }
    public OffsetDateTime now() { return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC); }
    public String number(UUID id) { return "B"+DateTimeFormatter.ofPattern("yyyyMMdd").format(clock.instant().atZone(ZONE))+id.toString().replace("-","").substring(0,16).toUpperCase(Locale.ROOT); }
    public static BusinessException missing(String code,String message) { return new BusinessException(HttpStatus.NOT_FOUND,code,message); }
    public static BusinessException bad(String code,String message) { return new BusinessException(HttpStatus.BAD_REQUEST,code,message); }
}
