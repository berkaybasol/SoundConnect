BEGIN;

-- The band service holds the parent lock through deletion. A retained actor is the
-- identity on job, review and report history and must never be physically removed.
CREATE OR REPLACE FUNCTION soundconnect_close_band_collab()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actor_id uuid;
    changed_at timestamptz;
BEGIN
    SELECT id INTO actor_id FROM tbl_collab_actor
    WHERE profile_type='BAND' AND source_profile_id=OLD.id FOR UPDATE;
    IF actor_id IS NULL THEN RETURN OLD; END IF;

    PERFORM id FROM tbl_collab WHERE id IN (
        SELECT id FROM tbl_collab WHERE publisher_actor_id=actor_id
        UNION ALL
        SELECT collab_id FROM tbl_collab_application WHERE applicant_actor_id=actor_id
    ) ORDER BY expires_at, id FOR NO KEY UPDATE;
    changed_at := clock_timestamp();

    DELETE FROM tbl_collab_saved_listing WHERE collab_id IN
        (SELECT id FROM tbl_collab WHERE publisher_actor_id=actor_id);
    UPDATE tbl_collab_application app
    SET status=CASE WHEN listing.publisher_actor_id=actor_id THEN 'INVALIDATED_BY_LISTING_CLOSURE'
                    ELSE 'WITHDRAWN_BY_APPLICANT' END,
        status_changed_at=greatest(changed_at,app.submitted_at), updated_at=changed_at AT TIME ZONE 'UTC',
        version=app.version+1
    FROM tbl_collab listing WHERE app.collab_id=listing.id AND app.status='PENDING'
          AND (listing.publisher_actor_id=actor_id OR app.applicant_actor_id=actor_id);
    UPDATE tbl_collab_job SET status='CANCELLED', updated_at=changed_at AT TIME ZONE 'UTC', version=version+1
    WHERE (publisher_actor_id=actor_id OR applicant_actor_id=actor_id) AND status='ACTIVE';
    UPDATE tbl_collab SET status='CLOSED', closure_reason='OWNER_CLOSED', closed_at=greatest(changed_at,published_at),
        updated_at=changed_at AT TIME ZONE 'UTC', version=version+1 WHERE publisher_actor_id=actor_id AND status='OPEN';
    DELETE FROM tbl_collab_genre WHERE collab_id IN
        (SELECT id FROM tbl_collab WHERE publisher_actor_id=actor_id AND status='DRAFT');
    DELETE FROM tbl_collab WHERE publisher_actor_id=actor_id AND status='DRAFT';
    UPDATE tbl_collab_actor SET active=false,avatar_url=null,updated_at=changed_at AT TIME ZONE 'UTC',version=version+1
    WHERE id=actor_id;
    RETURN OLD;
END;
$$;
DROP TRIGGER IF EXISTS trg_soundconnect_close_band_collab ON tbl_band;
CREATE TRIGGER trg_soundconnect_close_band_collab BEFORE DELETE ON tbl_band
FOR EACH ROW EXECUTE FUNCTION soundconnect_close_band_collab();

-- Media and track owners are polymorphic UUIDs. The parent lock also fences an
-- insert whose request passed application authorization just before band closure.
CREATE OR REPLACE FUNCTION soundconnect_guard_band_publication() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    target_id uuid;
BEGIN
    IF TG_OP='UPDATE' AND (to_jsonb(NEW)->TG_ARGV[0]) IS NOT DISTINCT FROM (to_jsonb(OLD)->TG_ARGV[0])
        AND (to_jsonb(NEW)->TG_ARGV[1]) IS NOT DISTINCT FROM (to_jsonb(OLD)->TG_ARGV[1]) THEN
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW)->>TG_ARGV[0])='BAND' THEN
        target_id := (to_jsonb(NEW)->>TG_ARGV[1])::uuid;
        PERFORM id FROM tbl_band WHERE id=target_id FOR SHARE;
        IF NOT FOUND THEN RAISE EXCEPTION 'Band is closed' USING ERRCODE='23514', CONSTRAINT='ck_closed_band'; END IF;
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS tr_band_publication_owner ON tbl_media_asset;
CREATE TRIGGER tr_band_publication_owner BEFORE INSERT OR UPDATE OF owner_type,owner_id ON tbl_media_asset
FOR EACH ROW EXECUTE FUNCTION soundconnect_guard_band_publication('owner_type','owner_id');
DROP TRIGGER IF EXISTS tr_band_publication_owner ON tbl_tracks;
CREATE TRIGGER tr_band_publication_owner BEFORE INSERT OR UPDATE OF owner_type,owner_id ON tbl_tracks
FOR EACH ROW EXECUTE FUNCTION soundconnect_guard_band_publication('owner_type','owner_id');
DROP TRIGGER IF EXISTS tr_band_publication_owner ON tbl_profile_media;
CREATE TRIGGER tr_band_publication_owner BEFORE INSERT OR UPDATE OF profile_type,profile_id ON tbl_profile_media
FOR EACH ROW EXECUTE FUNCTION soundconnect_guard_band_publication('profile_type','profile_id');

COMMIT;
