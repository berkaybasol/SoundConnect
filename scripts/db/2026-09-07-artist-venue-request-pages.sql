-- Source-only deployment migration. Run with autocommit enabled:
-- CREATE INDEX CONCURRENTLY cannot run inside a transaction block.
-- These are non-unique indexes; no historical request is rewritten or removed.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_artist_venue_request_musician_page
    ON artist_venue_connection_requests (musician_profile_id, request_by_type, status, created_at DESC, id DESC)
    WHERE musician_profile_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_artist_venue_request_band_page
    ON artist_venue_connection_requests (band_id, request_by_type, status, created_at DESC, id DESC)
    WHERE band_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_artist_venue_request_venue_page
    ON artist_venue_connection_requests (venue_id, request_by_type, status, created_at DESC, id DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_artist_venue_request_musician_pair
    ON artist_venue_connection_requests (musician_profile_id, venue_id, status)
    WHERE musician_profile_id IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_artist_venue_request_band_pair
    ON artist_venue_connection_requests (band_id, venue_id, status)
    WHERE band_id IS NOT NULL;
