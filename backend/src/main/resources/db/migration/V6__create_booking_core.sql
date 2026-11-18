CREATE TABLE booking_holds (
    id UUID PRIMARY KEY,
    member_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE','CONSUMED','RELEASED','EXPIRED')),
    expires_at TIMESTAMPTZ NOT NULL,
    pricing_version BIGINT NOT NULL REFERENCES pricing_versions(version),
    total_amount BIGINT NOT NULL CHECK (total_amount > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_booking_holds_member ON booking_holds(member_id, created_at);
CREATE TABLE hold_items (
    id UUID PRIMARY KEY,
    hold_id UUID NOT NULL REFERENCES booking_holds(id) ON DELETE CASCADE,
    court_id UUID NOT NULL REFERENCES courts(id),
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL CHECK (end_at = start_at + INTERVAL '1 hour'),
    unit_price BIGINT NOT NULL CHECK (unit_price BETWEEN 1 AND 100000),
    UNIQUE (hold_id, court_id, start_at)
);
CREATE TABLE booking_orders (
    id UUID PRIMARY KEY,
    order_number VARCHAR(32) NOT NULL UNIQUE,
    member_id UUID REFERENCES users(id) ON DELETE CASCADE,
    created_by UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    hold_id UUID UNIQUE REFERENCES booking_holds(id) ON DELETE CASCADE,
    source VARCHAR(20) NOT NULL CHECK (source IN ('MEMBER','WALK_IN','PHONE')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING_PAYMENT','CONFIRMED','CANCELLED','EXPIRED')),
    payment_status VARCHAR(20) NOT NULL CHECK (payment_status IN ('UNPAID','PAID')),
    payment_method VARCHAR(20) NOT NULL CHECK (payment_method IN ('CREDIT_CARD','CASH','ONSITE_CARD')),
    paid_reference VARCHAR(100) UNIQUE,
    refund_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED' CHECK (refund_status IN ('NOT_REQUIRED','PENDING_REVIEW')),
    customer_name VARCHAR(100) NOT NULL,
    customer_phone VARCHAR(20) NOT NULL,
    notes VARCHAR(500) NOT NULL DEFAULT '',
    total_amount BIGINT NOT NULL CHECK (total_amount > 0),
    pricing_version BIGINT NOT NULL REFERENCES pricing_versions(version),
    expires_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    cancellation_reason VARCHAR(200),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_booking_orders_member ON booking_orders(member_id, created_at DESC);
CREATE TABLE booking_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES booking_orders(id) ON DELETE CASCADE,
    court_id UUID NOT NULL REFERENCES courts(id),
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL CHECK (end_at = start_at + INTERVAL '1 hour'),
    unit_price BIGINT NOT NULL CHECK (unit_price BETWEEN 1 AND 100000),
    UNIQUE (order_id, court_id, start_at)
);
CREATE INDEX idx_booking_items_date ON booking_items(start_at, order_id);
CREATE TABLE slot_claims (
    court_id UUID NOT NULL REFERENCES courts(id),
    start_at TIMESTAMPTZ NOT NULL,
    hold_id UUID,
    order_id UUID,
    expires_at TIMESTAMPTZ,
    PRIMARY KEY (court_id, start_at),
    CONSTRAINT slot_claims_one_owner CHECK (num_nonnulls(hold_id, order_id) = 1),
    FOREIGN KEY (hold_id, court_id, start_at) REFERENCES hold_items(hold_id, court_id, start_at) ON DELETE CASCADE,
    FOREIGN KEY (order_id, court_id, start_at) REFERENCES booking_items(order_id, court_id, start_at) ON DELETE CASCADE
);
CREATE INDEX idx_slot_claims_expiry ON slot_claims(expires_at) WHERE expires_at IS NOT NULL;
CREATE TABLE booking_request_keys (
    actor_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    operation VARCHAR(30) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    resource_id UUID NOT NULL,
    PRIMARY KEY (actor_id, operation, request_key)
);
