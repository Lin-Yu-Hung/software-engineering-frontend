-- Retired courts keep their historical name; available names may be reused.
CREATE UNIQUE INDEX idx_courts_live_name
    ON courts(lower(name)) WHERE status <> 'RETIRED';
