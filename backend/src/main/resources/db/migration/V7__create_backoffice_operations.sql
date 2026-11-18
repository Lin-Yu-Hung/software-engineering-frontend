ALTER TABLE booking_orders ADD COLUMN checked_in_at TIMESTAMPTZ;
ALTER TABLE booking_orders ADD COLUMN checked_in_by UUID REFERENCES users(id);
ALTER TABLE booking_orders ADD CONSTRAINT check_in_pair CHECK
    ((checked_in_at IS NULL) = (checked_in_by IS NULL));

CREATE TABLE court_blocks (
    id UUID PRIMARY KEY,
    reason VARCHAR(200) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMPTZ
);
CREATE TABLE block_items (
    block_id UUID NOT NULL REFERENCES court_blocks(id) ON DELETE CASCADE,
    court_id UUID NOT NULL REFERENCES courts(id),
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (block_id, court_id, start_at),
    CHECK (end_at = start_at + INTERVAL '1 hour')
);
ALTER TABLE slot_claims ADD COLUMN block_id UUID;
ALTER TABLE slot_claims DROP CONSTRAINT slot_claims_one_owner;
ALTER TABLE slot_claims ADD CONSTRAINT slot_claims_one_owner
    CHECK (num_nonnulls(hold_id, order_id, block_id) = 1);
ALTER TABLE slot_claims ADD CONSTRAINT slot_claims_block_item
    FOREIGN KEY (block_id, court_id, start_at)
    REFERENCES block_items(block_id, court_id, start_at) ON DELETE CASCADE;
