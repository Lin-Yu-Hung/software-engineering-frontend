package com.booking.payment;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {
    public record Transaction(UUID id, UUID orderId, long amount, String status) { }
    private final JdbcTemplate jdbc;
    public PaymentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(UUID id, UUID orderId, String merchantTradeNo, long amount) {
        jdbc.update("INSERT INTO payment_transactions(id,order_id,merchant_trade_no,amount,status) VALUES (?,?,?,?,'INITIATED')",
                id, orderId, merchantTradeNo, amount);
    }
    public Optional<Transaction> find(String merchantTradeNo) {
        return jdbc.query("SELECT id,order_id,amount,status FROM payment_transactions WHERE merchant_trade_no=?",
                (rs, row) -> new Transaction(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3), rs.getString(4)),
                merchantTradeNo).stream().findFirst();
    }
    public void record(UUID id, String status, String gatewayTradeNo, Integer rtnCode, String rtnMsg, String raw) {
        jdbc.update("""
                UPDATE payment_transactions SET status=?,gateway_trade_no=?,rtn_code=?,rtn_msg=?,raw_notify=?,notified_at=CURRENT_TIMESTAMP
                WHERE id=?""", status, cut(gatewayTradeNo, 20), rtnCode, cut(rtnMsg, 200), raw, id);
    }
    private static String cut(String value, int max) { return value == null || value.length() <= max ? value : value.substring(0, max); }
}
