-- One row per ECPay checkout attempt. ECPay never accepts a reused MerchantTradeNo,
-- so a retry after a failed card gets a new row instead of reusing the order number.
CREATE TABLE payment_transactions (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES booking_orders(id) ON DELETE CASCADE,
    merchant_trade_no VARCHAR(20) NOT NULL UNIQUE CHECK (merchant_trade_no ~ '^[A-Za-z0-9]{1,20}$'),
    amount BIGINT NOT NULL CHECK (amount > 0),
    -- NEEDS_REFUND: ECPay charged the card but the order could no longer accept it
    -- (hold expired, cancelled, already paid by another attempt, amount mismatch).
    status VARCHAR(20) NOT NULL CHECK (status IN ('INITIATED','PAID','FAILED','NEEDS_REFUND')),
    gateway_trade_no VARCHAR(20),
    rtn_code INT,
    rtn_msg VARCHAR(200),
    raw_notify TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notified_at TIMESTAMPTZ
);
CREATE INDEX idx_payment_transactions_order ON payment_transactions(order_id, created_at DESC);
