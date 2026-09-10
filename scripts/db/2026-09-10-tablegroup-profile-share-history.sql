-- Apply after tablegroup-profile-shares, before deploying the matching API.
-- Additive and rerunnable: published identities and engagement are preserved.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE tbl_table_group_profile_share ADD COLUMN IF NOT EXISTS final_source jsonb;
ALTER TABLE tbl_table_group_profile_share ADD COLUMN IF NOT EXISTS final_source_frozen boolean NOT NULL DEFAULT false;

-- Serialize deployment with source/membership writers while installing and
-- backfilling lifecycle hooks. Runtime calls use the existing aggregate lock.
LOCK TABLE tbl_table_group, tbl_table_group_participants IN SHARE ROW EXCLUSIVE MODE;

CREATE OR REPLACE FUNCTION soundconnect_freeze_table_profile_shares(source_id uuid, terminal_status text)
RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
    source tbl_table_group%ROWTYPE;
    snapshot jsonb;
    ended_status text;
    changed integer;
BEGIN
    SELECT * INTO source FROM tbl_table_group WHERE id=source_id FOR UPDATE;
    IF NOT FOUND THEN RETURN 0; END IF;
    -- Natural expiry takes precedence when a later cleanup changes the row.
    -- A table already cancelled before its deadline retains CANCELLED forever.
    ended_status := CASE
        WHEN source.status='CANCELLED' THEN 'CANCELLED'
        WHEN source.status='INACTIVE' OR source.expires_at<=clock_timestamp() THEN 'INACTIVE'
        WHEN terminal_status IN ('INACTIVE','CANCELLED') THEN terminal_status
        ELSE NULL END;
    IF ended_status IS NULL THEN RETURN 0; END IF;
    IF NOT EXISTS (SELECT 1 FROM tbl_table_group_profile_share
                   WHERE table_group_id=source_id AND NOT final_source_frozen) THEN RETURN 0; END IF;

    SELECT jsonb_build_object(
        'id',source.id, 'description',source.description, 'venueName',source.venue_name,
        'cityName',c.name, 'districtName',d.name, 'meetingAt',source.meeting_at,
        'expiresAt',source.expires_at, 'status',ended_status, 'maxPersonCount',source.max_person_count,
        'acceptedCount',(SELECT count(*) FROM tbl_table_group_participants p
                         WHERE p.table_group_id=source.id AND p.status='ACCEPTED'))
    INTO snapshot FROM tbl_city c LEFT JOIN tbl_district d ON d.id=source.district_id WHERE c.id=source.city_id;
    IF snapshot IS NULL THEN RAISE EXCEPTION 'Table profile snapshot requires valid source location'; END IF;

    -- Freeze the negative decision as well: an old LEFT/KICKED publication must
    -- never reappear because terminal membership rows are later removed/reused.
    UPDATE tbl_table_group_profile_share s SET final_source_frozen=true,
        final_source=CASE WHEN s.owner_user_id=source.owner_id OR EXISTS (
            SELECT 1 FROM tbl_table_group_participants p WHERE p.table_group_id=source.id
                AND p.user_id=s.owner_user_id AND p.status='ACCEPTED') THEN snapshot ELSE NULL END
    WHERE s.table_group_id=source_id AND NOT s.final_source_frozen;
    GET DIAGNOSTICS changed=ROW_COUNT;
    RETURN changed;
END $$;

-- Capture the OLD public state before closure or any mutation after expiry.
-- Hibernate collection replacement and account erasure can remove accepted
-- rows; the participant hook freezes them before the first row changes.
CREATE OR REPLACE FUNCTION soundconnect_table_profile_source_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('INACTIVE','CANCELLED') OR OLD.expires_at<=clock_timestamp()
       OR NEW.status IN ('INACTIVE','CANCELLED') THEN
        PERFORM soundconnect_freeze_table_profile_shares(OLD.id, NEW.status);
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS tr_table_profile_source_transition ON tbl_table_group;
CREATE TRIGGER tr_table_profile_source_transition BEFORE UPDATE ON tbl_table_group
FOR EACH ROW EXECUTE FUNCTION soundconnect_table_profile_source_transition();

CREATE OR REPLACE FUNCTION soundconnect_table_profile_participant_transition()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE source_id uuid;
BEGIN
    source_id := CASE WHEN TG_OP='INSERT' THEN NEW.table_group_id ELSE OLD.table_group_id END;
    IF EXISTS (SELECT 1 FROM tbl_table_group t WHERE t.id=source_id
               AND (t.status IN ('INACTIVE','CANCELLED') OR t.expires_at<=clock_timestamp())) THEN
        PERFORM soundconnect_freeze_table_profile_shares(source_id, NULL);
    END IF;
    RETURN CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
END $$;
DROP TRIGGER IF EXISTS tr_table_profile_participant_transition ON tbl_table_group_participants;
CREATE TRIGGER tr_table_profile_participant_transition BEFORE INSERT OR UPDATE OR DELETE ON tbl_table_group_participants
FOR EACH ROW EXECUTE FUNCTION soundconnect_table_profile_participant_transition();

CREATE OR REPLACE FUNCTION soundconnect_table_profile_snapshot_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.final_source_frozen AND (NOT NEW.final_source_frozen OR NEW.final_source IS DISTINCT FROM OLD.final_source) THEN
        RAISE EXCEPTION 'Final table profile snapshot is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS tr_table_profile_snapshot_immutable ON tbl_table_group_profile_share;
CREATE TRIGGER tr_table_profile_snapshot_immutable BEFORE UPDATE OF final_source,final_source_frozen ON tbl_table_group_profile_share
FOR EACH ROW EXECUTE FUNCTION soundconnect_table_profile_snapshot_immutable();

DO $snapshot_constraint$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='tbl_table_group_profile_share'::regclass
                   AND conname='ck_table_profile_final_source') THEN
        ALTER TABLE tbl_table_group_profile_share ADD CONSTRAINT ck_table_profile_final_source CHECK (
            final_source IS NULL OR ((final_source_frozen AND jsonb_typeof(final_source)='object'
                AND final_source->>'id'=table_group_id::text
                AND final_source->>'status' IN ('INACTIVE','CANCELLED')
                AND jsonb_typeof(final_source->'description')='string'
                AND nullif(btrim(final_source->>'description'),'') IS NOT NULL
                AND jsonb_typeof(final_source->'cityName')='string'
                AND nullif(btrim(final_source->>'cityName'),'') IS NOT NULL
                AND jsonb_typeof(final_source->'meetingAt')='string'
                AND jsonb_typeof(final_source->'expiresAt')='string'
                AND jsonb_typeof(final_source->'maxPersonCount')='number'
                AND jsonb_typeof(final_source->'acceptedCount')='number'
                AND (final_source->>'maxPersonCount')::numeric BETWEEN 2 AND 6
                AND (final_source->>'acceptedCount')::numeric BETWEEN 0 AND (final_source->>'maxPersonCount')::numeric
                AND final_source ?& ARRAY['id','description','venueName','cityName','districtName',
                    'meetingAt','expiresAt','status','maxPersonCount','acceptedCount']
                AND final_source - ARRAY['id','description','venueName','cityName','districtName',
                    'meetingAt','expiresAt','status','maxPersonCount','acceptedCount'] = '{}'::jsonb) IS TRUE));
    END IF;
END $snapshot_constraint$;

-- Historical rows use the currently retained state; information already erased
-- before this migration cannot be reconstructed. Reruns never rewrite history.
DO $backfill$
DECLARE source_id uuid;
BEGIN
    FOR source_id IN SELECT DISTINCT t.id FROM tbl_table_group t
        JOIN tbl_table_group_profile_share s ON s.table_group_id=t.id
        WHERE NOT s.final_source_frozen AND (t.status IN ('INACTIVE','CANCELLED') OR t.expires_at<=clock_timestamp())
        ORDER BY t.id
    LOOP PERFORM soundconnect_freeze_table_profile_shares(source_id,NULL); END LOOP;
END $backfill$;

INSERT INTO soundconnect_schema_migrations(migration_id) VALUES('2026-09-10-tablegroup-profile-share-history')
    ON CONFLICT DO NOTHING;
COMMIT;
