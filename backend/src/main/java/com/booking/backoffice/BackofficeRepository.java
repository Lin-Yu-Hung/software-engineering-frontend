package com.booking.backoffice;

import static com.booking.backoffice.BackofficeModels.*;
import static com.booking.booking.BookingModels.*;
import static com.booking.venue.ScheduleModels.ZONE;
import com.booking.booking.BookingRepository;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BackofficeRepository {
    private final JdbcTemplate jdbc;
    private final BookingRepository orders;
    public BackofficeRepository(JdbcTemplate jdbc,BookingRepository orders) { this.jdbc=jdbc;this.orders=orders; }
    private List<Object> dateArgs(LocalDate date) {
        return new ArrayList<>(List.of(date.atStartOfDay(ZONE).toOffsetDateTime(),date.plusDays(1).atStartOfDay(ZONE).toOffsetDateTime()));
    }
    private static final String ON_DATE=" EXISTS (SELECT 1 FROM booking_items i WHERE i.order_id=o.id AND i.start_at>=? AND i.start_at<?)";
    public OrderPage list(LocalDate date,int page,int size) {
        String where=date==null?"":" WHERE"+ON_DATE;
        List<Object> args=date==null?new ArrayList<>():dateArgs(date);
        long total=jdbc.queryForObject("SELECT count(*) FROM booking_orders o"+where,Long.class,args.toArray());
        args.add(size);args.add(Math.multiplyExact((long)page,size));
        var ids=jdbc.query("SELECT id FROM booking_orders o"+where+" ORDER BY created_at DESC,id LIMIT ? OFFSET ?",(rs,row)->rs.getObject(1,UUID.class),args.toArray());
        return new OrderPage(ids.stream().map(id->orders.order(id).orElseThrow()).toList(),page,size,total,(total+size-1)/size);
    }
    public List<OrderResponse> timetableOrders(LocalDate date) {
        return jdbc.query("SELECT id FROM booking_orders o WHERE status IN ('PENDING_PAYMENT','CONFIRMED') AND"+ON_DATE+" ORDER BY created_at,id",
                (rs,row)->rs.getObject(1,UUID.class),dateArgs(date).toArray()).stream().map(id->orders.order(id).orElseThrow()).toList();
    }
    public void edit(UUID id,OrderEdit request) {
        jdbc.update("UPDATE booking_orders SET customer_name=?,customer_phone=?,notes=?,version=version+1 WHERE id=?",
                request.customer().name().strip(),request.customer().phone().strip(),request.notes().strip(),id);
    }
    public void checkIn(UUID id,UUID actor,OffsetDateTime at) {
        jdbc.update("UPDATE booking_orders SET checked_in_at=?,checked_in_by=?,version=version+1 WHERE id=?",at,actor,id);
    }
    public void reverseCheckIn(UUID id) {
        jdbc.update("UPDATE booking_orders SET checked_in_at=NULL,checked_in_by=NULL,version=version+1 WHERE id=?",id);
    }
    public void block(UUID id,UUID actor,BlockRequest request,List<Item> items) {
        jdbc.update("INSERT INTO court_blocks(id,reason,created_by) VALUES (?,?,?)",id,request.reason().strip(),actor);
        for(var item:items) {
            jdbc.update("INSERT INTO block_items VALUES (?,?,?,?)",id,item.courtId(),item.startAt(),item.endAt());
            jdbc.update("INSERT INTO slot_claims(court_id,start_at,block_id) VALUES (?,?,?)",item.courtId(),item.startAt(),id);
        }
    }
    public Optional<BlockResponse> block(UUID id) {
        return jdbc.query("SELECT * FROM court_blocks WHERE id=?",(rs,row)->new BlockResponse(id,rs.getString("reason"),rs.getBoolean("active"),
                rs.getObject("created_at",OffsetDateTime.class),rs.getObject("released_at",OffsetDateTime.class),
                jdbc.query("SELECT * FROM block_items WHERE block_id=? ORDER BY start_at,court_id",(r,n)->new BlockSlot(r.getObject("court_id",UUID.class),r.getObject("start_at",OffsetDateTime.class),r.getObject("end_at",OffsetDateTime.class)),id)),id).stream().findFirst();
    }
    public List<BlockResponse> blocks(LocalDate date) {
        return jdbc.query("SELECT id FROM court_blocks b WHERE active AND EXISTS (SELECT 1 FROM block_items i WHERE i.block_id=b.id AND i.start_at>=? AND i.start_at<?) ORDER BY created_at,id",
                (rs,row)->rs.getObject(1,UUID.class),dateArgs(date).toArray()).stream().map(id->block(id).orElseThrow()).toList();
    }
    public void releaseBlock(UUID id,OffsetDateTime at) {
        jdbc.update("DELETE FROM slot_claims WHERE block_id=?",id);
        jdbc.update("UPDATE court_blocks SET active=FALSE,released_at=? WHERE id=? AND active",at,id);
    }
}
