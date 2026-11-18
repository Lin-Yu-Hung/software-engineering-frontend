package com.booking.booking;

import static com.booking.booking.BookingModels.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BookingRepository {
    private final JdbcTemplate jdbc;
    public BookingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<UUID> replay(UUID actor, String operation, String key, String hash) {
        return jdbc.query("SELECT request_hash,resource_id FROM booking_request_keys WHERE actor_id=? AND operation=? AND request_key=?",
                (rs, row) -> {
                    if (!rs.getString(1).equals(hash)) throw InventoryRepository.conflict("IDEMPOTENCY_CONFLICT", "同一 Idempotency-Key 不可用於不同請求");
                    return rs.getObject(2, UUID.class);
                }, actor, operation, key).stream().findFirst();
    }
    public void remember(UUID actor, String operation, String key, String hash, UUID id) {
        jdbc.update("INSERT INTO booking_request_keys VALUES (?,?,?,?,?)", actor, operation, key, hash, id);
    }
    public void insertHold(UUID id, UUID member, PricedSlots priced, OffsetDateTime expiry) {
        jdbc.update("INSERT INTO booking_holds(id,member_id,status,expires_at,pricing_version,total_amount) VALUES (?,?,'ACTIVE',?,?,?)",
                id, member, expiry, priced.pricingVersion(), priced.totalAmount());
        for (Item item : priced.items()) {
            jdbc.update("INSERT INTO hold_items VALUES (?,?,?,?,?,?)", item.id(), id, item.courtId(), item.startAt(), item.endAt(), item.unitPrice());
            jdbc.update("INSERT INTO slot_claims(court_id,start_at,hold_id,expires_at) VALUES (?,?,?,?)", item.courtId(), item.startAt(), id, expiry);
        }
    }
    public long activeHoldCount(UUID member) {
        return jdbc.queryForObject("SELECT count(*) FROM booking_holds WHERE member_id=? AND status='ACTIVE'", Long.class, member);
    }
    public Optional<HoldResponse> hold(UUID id, UUID member) {
        return jdbc.query("SELECT h.*,o.id AS linked_order FROM booking_holds h LEFT JOIN booking_orders o ON o.hold_id=h.id WHERE h.id=? AND h.member_id=?",
                (rs,row) -> new HoldResponse(id, HoldStatus.valueOf(rs.getString("status")), rs.getObject("expires_at", OffsetDateTime.class),
                        "TWD", rs.getLong("total_amount"), rs.getLong("pricing_version"), rs.getObject("linked_order", UUID.class), holdItems(id)), id, member).stream().findFirst();
    }
    public List<Item> holdItems(UUID id) { return items("hold_items", "hold_id", id); }
    public List<Item> orderItems(UUID id) { return items("booking_items", "order_id", id); }
    private List<Item> items(String table, String fk, UUID id) {
        // table/fk are internal constants, never request input.
        return jdbc.query("SELECT * FROM " + table + " WHERE " + fk + "=? ORDER BY start_at,court_id",
                (rs,row) -> new Item(rs.getObject("id", UUID.class), rs.getObject("court_id", UUID.class),
                        rs.getObject("start_at", OffsetDateTime.class), rs.getObject("end_at", OffsetDateTime.class), rs.getLong("unit_price")), id);
    }
    public void releaseHold(UUID id) {
        jdbc.update("DELETE FROM slot_claims WHERE hold_id=?", id);
        jdbc.update("UPDATE booking_holds SET status='RELEASED' WHERE id=? AND status='ACTIVE'", id);
    }
    public void insertOrder(UUID id, String number, UUID member, UUID actor, UUID hold, Source source,
            OrderStatus status, PaymentMethod method, Customer customer, String notes, PricedSlots priced, OffsetDateTime expires) {
        jdbc.update("""
                INSERT INTO booking_orders(id,order_number,member_id,created_by,hold_id,source,status,payment_status,
                payment_method,customer_name,customer_phone,notes,total_amount,pricing_version,expires_at)
                VALUES (?,?,?,?,?,?,?,'UNPAID',?,?,?,?,?,?,?)
                """, id,number,member,actor,hold,source.name(),status.name(),method.name(),customer.name().strip(),customer.phone().strip(),
                notes,priced.totalAmount(),priced.pricingVersion(),expires);
        for (Item item : priced.items()) jdbc.update("INSERT INTO booking_items VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(),id,item.courtId(),item.startAt(),item.endAt(),item.unitPrice());
        if (hold != null) {
            jdbc.update("UPDATE booking_holds SET status='CONSUMED' WHERE id=?", hold);
            jdbc.update("UPDATE slot_claims SET hold_id=NULL,order_id=? WHERE hold_id=?", id,hold);
        } else {
            for (Item item : priced.items()) jdbc.update("INSERT INTO slot_claims(court_id,start_at,order_id) VALUES (?,?,?)", item.courtId(),item.startAt(),id);
        }
    }
    public Optional<OrderResponse> order(UUID id) {
        return jdbc.query("SELECT * FROM booking_orders WHERE id=?", (rs,row) -> new OrderResponse(id,
                rs.getString("order_number"),rs.getObject("member_id", UUID.class),Source.valueOf(rs.getString("source")),
                OrderStatus.valueOf(rs.getString("status")),PaymentStatus.valueOf(rs.getString("payment_status")),
                PaymentMethod.valueOf(rs.getString("payment_method")),RefundStatus.valueOf(rs.getString("refund_status")),
                new Customer(rs.getString("customer_name"),rs.getString("customer_phone")),rs.getString("notes"),"TWD",
                rs.getLong("total_amount"),rs.getLong("pricing_version"),rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("created_at", OffsetDateTime.class),rs.getObject("cancelled_at", OffsetDateTime.class),
                rs.getString("cancellation_reason"),rs.getObject("checked_in_at",OffsetDateTime.class),rs.getLong("version"),orderItems(id)), id).stream().findFirst();
    }
    public OrderPage memberOrders(UUID member, String scope, int page, int size, OffsetDateTime now) {
        String filter = switch (scope) {
            case "upcoming" -> " AND status IN ('PENDING_PAYMENT','CONFIRMED') AND EXISTS (SELECT 1 FROM booking_items i WHERE i.order_id=o.id AND i.end_at>?)";
            case "past" -> " AND (status IN ('CANCELLED','EXPIRED') OR NOT EXISTS (SELECT 1 FROM booking_items i WHERE i.order_id=o.id AND i.end_at>?))";
            default -> "";
        };
        List<Object> args = new ArrayList<>(); args.add(member); if (!scope.equals("all")) args.add(now);
        long count = jdbc.queryForObject("SELECT count(*) FROM booking_orders o WHERE member_id=?"+filter, Long.class, args.toArray());
        args.add(size); args.add(Math.multiplyExact((long)page,size));
        var ids = jdbc.query("SELECT id FROM booking_orders o WHERE member_id=?"+filter+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",
                (rs,row) -> rs.getObject(1,UUID.class),args.toArray());
        return new OrderPage(ids.stream().map(id -> order(id).orElseThrow()).toList(),page,size,count,(count+size-1)/size);
    }
    public void cancel(UUID id, String reason, OffsetDateTime now) {
        jdbc.update("DELETE FROM slot_claims WHERE order_id=?",id);
        jdbc.update("UPDATE booking_orders SET status='CANCELLED',cancelled_at=?,cancellation_reason=?,refund_status=CASE WHEN payment_status='PAID' THEN 'PENDING_REVIEW' ELSE 'NOT_REQUIRED' END,version=version+1 WHERE id=?", now,reason.strip(),id);
    }
    public String paidReference(UUID id) { return jdbc.queryForObject("SELECT paid_reference FROM booking_orders WHERE id=?",String.class,id); }
    public void paid(UUID id, String reference) {
        jdbc.update("UPDATE booking_orders SET status='CONFIRMED',payment_status='PAID',paid_reference=?,expires_at=NULL,version=version+1 WHERE id=?",reference,id);
        jdbc.update("UPDATE slot_claims SET expires_at=NULL WHERE order_id=?",id);
    }
}
