-- Stable IDs make Postman examples repeatable on every teammate's database.
-- These are initial project demo courts, not a production venue configuration.
INSERT INTO courts (id, name, type, status, display_order) VALUES
    ('00000000-0000-0000-0000-000000000001', '第 1 場地', 'VIP 木地板', 'ACTIVE', 1),
    ('00000000-0000-0000-0000-000000000002', '第 2 場地', 'VIP 木地板', 'ACTIVE', 2),
    ('00000000-0000-0000-0000-000000000003', '第 3 場地', '標準 PU', 'ACTIVE', 3),
    ('00000000-0000-0000-0000-000000000004', '第 4 場地', '標準 PU', 'ACTIVE', 4),
    ('00000000-0000-0000-0000-000000000005', '第 5 場地', '標準 PU', 'ACTIVE', 5),
    ('00000000-0000-0000-0000-000000000006', '第 6 場地 (教學)', '訓練用 PU', 'ACTIVE', 6);
