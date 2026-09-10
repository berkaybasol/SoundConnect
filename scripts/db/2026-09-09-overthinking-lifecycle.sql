-- Run through the normal backed-up migration flow while application writers are stopped.
-- The trigger also covers track deletion through band teardown and bulk repository writes.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
LOCK TABLE tbl_tracks, tbl_overthinking_post, tbl_overthinking_reveal_request,
           tbl_comment, tbl_like IN SHARE ROW EXCLUSIVE MODE;

DELETE FROM tbl_like l
WHERE l.target_type = 'COMMENT' AND EXISTS (
    SELECT 1 FROM tbl_comment c
    WHERE c.id = l.target_id AND c.target_type = 'OVERTHINKING'
      AND NOT EXISTS (SELECT 1 FROM tbl_overthinking_post p WHERE p.id = c.target_id)
);
DELETE FROM tbl_comment c
WHERE c.target_type = 'OVERTHINKING' AND c.parent_comment_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM tbl_overthinking_post p WHERE p.id = c.target_id);
DELETE FROM tbl_comment c
WHERE c.target_type = 'OVERTHINKING' AND c.parent_comment_id IS NULL
  AND NOT EXISTS (SELECT 1 FROM tbl_overthinking_post p WHERE p.id = c.target_id);
DELETE FROM tbl_like l
WHERE l.target_type = 'OVERTHINKING'
  AND NOT EXISTS (SELECT 1 FROM tbl_overthinking_post p WHERE p.id = l.target_id);

-- Repair plain UUID references left by the previous track deletion implementation.
UPDATE tbl_overthinking_post p SET musician_track_id = NULL
WHERE musician_track_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM tbl_tracks t WHERE t.id = p.musician_track_id);
UPDATE tbl_overthinking_post p SET band_track_id = NULL
WHERE band_track_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM tbl_tracks t WHERE t.id = p.band_track_id);
UPDATE tbl_overthinking_post
SET artist_id = NULL, artist_type = NULL, spotify_artist_id = NULL,
    spotify_track_name = NULL, spotify_artist_name = NULL, spotify_album_image_url = NULL
WHERE nullif(trim(spotify_track_url), '') IS NULL
  AND musician_track_id IS NULL AND band_track_id IS NULL;

-- Old Spotify account attribution trusted the request's artist ID. Clear that
-- binding once; a later verified edit can restore it without losing link/metadata.
UPDATE tbl_overthinking_post SET artist_id = NULL, artist_type = NULL
WHERE nullif(trim(spotify_track_url), '') IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM soundconnect_schema_migrations
                  WHERE migration_id = '2026-09-09-overthinking-lifecycle');

CREATE INDEX IF NOT EXISTS idx_overthinking_musician_track
    ON tbl_overthinking_post(musician_track_id) WHERE musician_track_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_overthinking_band_track
    ON tbl_overthinking_post(band_track_id) WHERE band_track_id IS NOT NULL;

CREATE OR REPLACE FUNCTION detach_overthinking_track() RETURNS trigger
LANGUAGE plpgsql AS $function$
BEGIN
    -- Stable order also tolerates malformed legacy rows referencing both sources.
    PERFORM id FROM tbl_overthinking_post
      WHERE musician_track_id = OLD.id OR band_track_id = OLD.id
      ORDER BY id FOR UPDATE;
    UPDATE tbl_overthinking_post
    SET musician_track_id = CASE WHEN musician_track_id = OLD.id THEN NULL ELSE musician_track_id END,
        band_track_id = CASE WHEN band_track_id = OLD.id THEN NULL ELSE band_track_id END,
        artist_id = CASE WHEN nullif(trim(spotify_track_url), '') IS NULL THEN NULL ELSE artist_id END,
        artist_type = CASE WHEN nullif(trim(spotify_track_url), '') IS NULL THEN NULL ELSE artist_type END,
        spotify_artist_id = CASE WHEN nullif(trim(spotify_track_url), '') IS NULL THEN NULL ELSE spotify_artist_id END,
        spotify_track_name = CASE WHEN nullif(trim(spotify_track_url), '') IS NULL THEN NULL ELSE spotify_track_name END,
        spotify_artist_name = CASE WHEN nullif(trim(spotify_track_url), '') IS NULL THEN NULL ELSE spotify_artist_name END,
        spotify_album_image_url = CASE WHEN nullif(trim(spotify_track_url), '') IS NULL THEN NULL ELSE spotify_album_image_url END,
        updated_at = timezone('UTC', current_timestamp)
    WHERE musician_track_id = OLD.id OR band_track_id = OLD.id;
    RETURN OLD;
END;
$function$;

DROP TRIGGER IF EXISTS trg_detach_overthinking_track ON tbl_tracks;
CREATE TRIGGER trg_detach_overthinking_track BEFORE DELETE ON tbl_tracks
FOR EACH ROW EXECUTE FUNCTION detach_overthinking_track();

DO $constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_overthinking_musician_track'
                   AND conrelid = 'tbl_overthinking_post'::regclass) THEN
        ALTER TABLE tbl_overthinking_post ADD CONSTRAINT fk_overthinking_musician_track
            FOREIGN KEY (musician_track_id) REFERENCES tbl_tracks(id) ON DELETE SET NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_overthinking_band_track'
                   AND conrelid = 'tbl_overthinking_post'::regclass) THEN
        ALTER TABLE tbl_overthinking_post ADD CONSTRAINT fk_overthinking_band_track
            FOREIGN KEY (band_track_id) REFERENCES tbl_tracks(id) ON DELETE SET NULL;
    END IF;
END;
$constraints$;
INSERT INTO soundconnect_schema_migrations (migration_id)
VALUES ('2026-09-09-overthinking-lifecycle') ON CONFLICT (migration_id) DO NOTHING;
COMMIT;
