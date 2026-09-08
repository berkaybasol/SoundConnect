-- Additive lookup index for recipient-scoped pending invitation reads.
-- Run outside a transaction on PostgreSQL. No membership/event rows change.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_band_member_user_status
    ON tbl_band_member (user_id, status);
