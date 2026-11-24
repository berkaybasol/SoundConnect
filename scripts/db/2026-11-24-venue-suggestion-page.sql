-- Run with autocommit enabled so public suggestions can continue during the index build.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_venue_suggestion_page
    ON tbl_venue_suggestion (created_at DESC, id DESC);
