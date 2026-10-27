CREATE TABLE IF NOT EXISTS reservations (
    series_id UUID NULL
);

CREATE INDEX IF NOT EXISTS idx_reservation_series_id ON reservations(series_id);
