CREATE TABLE venue_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    name VARCHAR(100) NOT NULL,
    advance_booking_days INTEGER NOT NULL CHECK (advance_booking_days BETWEEN 1 AND 365),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO venue_settings(id, name, advance_booking_days) VALUES (1, '羽球預約示範場館', 30);

-- An empty day has no rows and means closed. End hour 24 is next-day midnight.
CREATE TABLE operating_hours (
    day_of_week INTEGER NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_hour INTEGER NOT NULL CHECK (start_hour BETWEEN 0 AND 23),
    end_hour INTEGER NOT NULL CHECK (end_hour BETWEEN 1 AND 24 AND end_hour > start_hour),
    PRIMARY KEY (day_of_week, start_hour)
);
INSERT INTO operating_hours SELECT day, 8, 22 FROM generate_series(1, 7) AS day;

CREATE TABLE calendar_exceptions (
    id UUID PRIMARY KEY,
    date DATE NOT NULL UNIQUE,
    label VARCHAR(100) NOT NULL CHECK (length(trim(label)) > 0),
    day_type VARCHAR(20) NOT NULL CHECK (day_type IN ('WEEKDAY', 'WEEKEND', 'HOLIDAY')),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE calendar_exception_hours (
    exception_id UUID NOT NULL REFERENCES calendar_exceptions(id) ON DELETE CASCADE,
    start_hour INTEGER NOT NULL CHECK (start_hour BETWEEN 0 AND 23),
    end_hour INTEGER NOT NULL CHECK (end_hour BETWEEN 1 AND 24 AND end_hour > start_hour),
    PRIMARY KEY (exception_id, start_hour)
);

-- Keep all published price sets; later orders can reference their pricing version.
CREATE TABLE pricing_versions (
    version BIGINT PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO pricing_versions(version) VALUES (0);
CREATE TABLE pricing_config (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    version BIGINT NOT NULL REFERENCES pricing_versions(version)
);
INSERT INTO pricing_config VALUES (1, 0);
CREATE TABLE pricing_rules (
    id UUID PRIMARY KEY,
    pricing_version BIGINT NOT NULL REFERENCES pricing_versions(version),
    court_id UUID REFERENCES courts(id),
    day_type VARCHAR(20) NOT NULL CHECK (day_type IN ('WEEKDAY', 'WEEKEND', 'HOLIDAY')),
    start_hour INTEGER NOT NULL CHECK (start_hour BETWEEN 0 AND 23),
    end_hour INTEGER NOT NULL CHECK (end_hour BETWEEN 1 AND 24 AND end_hour > start_hour),
    price BIGINT NOT NULL CHECK (price BETWEEN 1 AND 100000),
    price_type VARCHAR(20) NOT NULL CHECK (price_type IN ('OFF_PEAK', 'PEAK', 'WEEKEND', 'HOLIDAY', 'SPECIAL')),
    priority INTEGER NOT NULL CHECK (priority BETWEEN 0 AND 100)
);
CREATE INDEX idx_pricing_rules_version ON pricing_rules(pricing_version);

-- Venue fallbacks cover all hours, including newly created courts and changed hours.
INSERT INTO pricing_rules VALUES
    (gen_random_uuid(), 0, NULL, 'WEEKDAY', 0, 24, 350, 'OFF_PEAK', 0),
    (gen_random_uuid(), 0, NULL, 'WEEKDAY', 18, 24, 500, 'PEAK', 10),
    (gen_random_uuid(), 0, NULL, 'WEEKEND', 0, 24, 500, 'WEEKEND', 0),
    (gen_random_uuid(), 0, NULL, 'HOLIDAY', 0, 24, 500, 'HOLIDAY', 0);

-- Initial six-court prices match the existing frontend; no frontend IDs are used.
INSERT INTO pricing_rules
SELECT gen_random_uuid(), 0, c.id, p.day_type, p.start_hour, p.end_hour,
       CASE WHEN p.price_type = 'OFF_PEAK' THEN c.off_peak ELSE c.peak END,
       p.price_type, 0
FROM (VALUES
    ('00000000-0000-0000-0000-000000000001'::uuid, 350, 500),
    ('00000000-0000-0000-0000-000000000002'::uuid, 350, 500),
    ('00000000-0000-0000-0000-000000000003'::uuid, 300, 450),
    ('00000000-0000-0000-0000-000000000004'::uuid, 300, 450),
    ('00000000-0000-0000-0000-000000000005'::uuid, 300, 450),
    ('00000000-0000-0000-0000-000000000006'::uuid, 280, 400)
) AS c(id, off_peak, peak)
CROSS JOIN (VALUES
    ('WEEKDAY', 0, 18, 'OFF_PEAK'), ('WEEKDAY', 18, 24, 'PEAK'),
    ('WEEKEND', 0, 24, 'WEEKEND'), ('HOLIDAY', 0, 24, 'HOLIDAY')
) AS p(day_type, start_hour, end_hour, price_type);
