-- SoundConnect backend-authoritative attachment idempotency rollout.
--
-- Run with a client that stops on the first error (psql: ON_ERROR_STOP=1).
-- PostgreSQL DDL is transactional; the table locks, deterministic duplicate
-- cleanup, and unique constraints therefore become visible as one atomic unit.

BEGIN;

LOCK TABLE tbl_profile_media, tbl_tracks, tbl_overthinking_post IN ACCESS EXCLUSIVE MODE;

-- A historical retry could insert the same attachment more than once. Keep the
-- oldest business row; UUID provides a stable tie-breaker for legacy rows whose
-- audit timestamp is equal or absent.
WITH ranked_profile_media AS (
    SELECT id,
           row_number() OVER (
               PARTITION BY profile_type, profile_id, media_asset_id, role
               ORDER BY created_at ASC NULLS LAST, id ASC
           ) AS duplicate_rank
    FROM tbl_profile_media
)
DELETE FROM tbl_profile_media existing
USING ranked_profile_media ranked
WHERE existing.id = ranked.id
  AND ranked.duplicate_rank > 1;

-- Track row IDs are embedded as plain UUID references by overthinking posts.
-- Materialize the loser -> winner mapping once so every reference is moved in
-- the same transaction before any duplicate row is deleted.
CREATE TEMPORARY TABLE soundconnect_track_duplicate_map
ON COMMIT DROP
AS
WITH ranked_tracks AS (
    SELECT id,
           owner_type,
           first_value(id) OVER (
               PARTITION BY owner_type, owner_id, media_asset_id
               ORDER BY created_at ASC NULLS LAST, id ASC
           ) AS winner_id,
           row_number() OVER (
               PARTITION BY owner_type, owner_id, media_asset_id
               ORDER BY created_at ASC NULLS LAST, id ASC
           ) AS duplicate_rank
    FROM tbl_tracks
)
SELECT id AS loser_id, winner_id, owner_type
FROM ranked_tracks
WHERE duplicate_rank > 1;

UPDATE tbl_overthinking_post post
SET musician_track_id = duplicate.winner_id
FROM soundconnect_track_duplicate_map duplicate
WHERE duplicate.owner_type = 'MUSICIAN_PROFILE'
  AND post.musician_track_id = duplicate.loser_id;

UPDATE tbl_overthinking_post post
SET band_track_id = duplicate.winner_id
FROM soundconnect_track_duplicate_map duplicate
WHERE duplicate.owner_type = 'BAND'
  AND post.band_track_id = duplicate.loser_id;

DELETE FROM tbl_tracks existing
USING soundconnect_track_duplicate_map duplicate
WHERE existing.id = duplicate.loser_id;

ALTER TABLE tbl_profile_media
    DROP CONSTRAINT IF EXISTS uk_profile_media_attachment;

ALTER TABLE tbl_profile_media
    ADD CONSTRAINT uk_profile_media_attachment
    UNIQUE (profile_type, profile_id, media_asset_id, role);

ALTER TABLE tbl_tracks
    DROP CONSTRAINT IF EXISTS uk_tracks_owner_media_asset;

ALTER TABLE tbl_tracks
    ADD CONSTRAINT uk_tracks_owner_media_asset
    UNIQUE (owner_type, owner_id, media_asset_id);

COMMIT;
