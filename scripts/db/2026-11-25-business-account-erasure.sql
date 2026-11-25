-- Keep private booking/event history and fence stale writers against the retained aggregate.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
ALTER TABLE tbl_venues ADD COLUMN IF NOT EXISTS account_erased_at timestamptz;
ALTER TABLE tbl_studio_profile ADD COLUMN IF NOT EXISTS account_erased_at timestamptz;

CREATE OR REPLACE FUNCTION soundconnect_preserve_erased_business() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.account_erased_at IS NOT NULL THEN
        RAISE EXCEPTION 'Business account was erased' USING ERRCODE='23514', CONSTRAINT='ck_account_erased';
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION soundconnect_guard_business_target() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    scope text;
    owner_key uuid;
    erased timestamptz;
BEGIN
    IF TG_NARGS=2 THEN
        scope := TG_ARGV[0];
        owner_key := (to_jsonb(NEW)->>TG_ARGV[1])::uuid;
    ELSE
        -- Media removal and recovery continue updating the existing intent after the
        -- profile disappears. Only a new or changed ownership binding needs admission.
        IF TG_OP='UPDATE' AND to_jsonb(OLD)->TG_ARGV[1] IS NOT DISTINCT FROM to_jsonb(NEW)->TG_ARGV[1]
            AND to_jsonb(OLD)->TG_ARGV[2] IS NOT DISTINCT FROM to_jsonb(NEW)->TG_ARGV[2] THEN RETURN NEW; END IF;
        scope := to_jsonb(NEW)->>TG_ARGV[1];
        owner_key := (to_jsonb(NEW)->>TG_ARGV[2])::uuid;
        IF TG_ARGV[0]='PROFILE' AND scope='VENUE' THEN scope := 'VENUE_PROFILE'; END IF;
    END IF;
    IF owner_key IS NULL THEN RETURN NEW; END IF;
    IF scope='VENUE' THEN
        SELECT account_erased_at INTO erased FROM tbl_venues WHERE id=owner_key FOR SHARE;
    ELSIF scope='VENUE_PROFILE' THEN
        SELECT v.account_erased_at INTO erased FROM tbl_venues v JOIN tbl_venue_profile p ON p.venue_id=v.id
        WHERE p.id=owner_key FOR SHARE OF v;
    ELSIF scope IN ('STUDIO','STUDIO_PROFILE') THEN
        SELECT account_erased_at INTO erased FROM tbl_studio_profile WHERE id=owner_key FOR SHARE;
    ELSIF scope IN ('MUSICIAN','MUSICIAN_PROFILE') THEN
        PERFORM id FROM tbl_musician_profile WHERE id=owner_key FOR SHARE;
    ELSE RETURN NEW;
    END IF;
    IF NOT FOUND OR erased IS NOT NULL THEN
        RAISE EXCEPTION 'Business is unavailable' USING ERRCODE='23514', CONSTRAINT='ck_account_erased';
    END IF;
    RETURN NEW;
END $$;

DO $$
DECLARE t text; r record;
BEGIN
    FOREACH t IN ARRAY ARRAY['tbl_venues','tbl_studio_profile'] LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS tr_preserve_erased_business ON %I',t);
        EXECUTE format('CREATE TRIGGER tr_preserve_erased_business BEFORE UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION soundconnect_preserve_erased_business()',t);
    END LOOP;
    FOR r IN SELECT * FROM (VALUES
        ('tbl_venues','owner_id'),('tbl_studio_profile','user_id'),
        ('tbl_venue_applications','user_id'),('tbl_studio_applications','applicant_id'),
        ('tbl_musician_profile','user_id'),('tbl_band_member','user_id')
    ) AS roots(t,col) LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS tr_business_account ON %I',r.t);
        EXECUTE format('CREATE TRIGGER tr_business_account BEFORE INSERT OR UPDATE OF %I ON %I FOR EACH ROW EXECUTE FUNCTION soundconnect_reject_erased_reference(%L)',r.col,r.t,r.col);
    END LOOP;
    FOR r IN SELECT * FROM (VALUES
        ('tbl_event','VENUE','venue_id'),('event_plans','VENUE','venue_id'),('event_venue_requests','VENUE','venue_id'),
        ('artist_venue_connection_requests','VENUE','venue_id'),('musician_profile_venues','VENUE','venue_id'),
        ('venue_active_bands','VENUE','venue_id'),('tbl_venue_profile','VENUE','venue_id'),
        ('tbl_studio_room','STUDIO','studio_profile_id'),('tbl_studio_equipment','STUDIO','studio_profile_id'),
        ('tbl_backline_category_request','STUDIO','studio_profile_id')
    ) AS dependents(t,scope,col) LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS tr_business_target ON %I',r.t);
        EXECUTE format('CREATE TRIGGER tr_business_target BEFORE INSERT OR UPDATE OF %I ON %I FOR EACH ROW EXECUTE FUNCTION soundconnect_guard_business_target(%L,%L)',r.col,r.t,r.scope,r.col);
    END LOOP;
    FOR r IN SELECT * FROM (VALUES
        ('tbl_media_asset','OWNER','owner_type','owner_id'),
        ('tbl_tracks','OWNER','owner_type','owner_id'),
        ('tbl_profile_media','PROFILE','profile_type','profile_id')
    ) AS publications(t,kind,type_col,id_col) LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS tr_business_scope ON %I',r.t);
        EXECUTE format('CREATE TRIGGER tr_business_scope BEFORE INSERT OR UPDATE OF %I,%I ON %I FOR EACH ROW EXECUTE FUNCTION soundconnect_guard_business_target(%L,%L,%L)',r.type_col,r.id_col,r.t,r.kind,r.type_col,r.id_col);
    END LOOP;
END $$;
INSERT INTO soundconnect_schema_migrations(migration_id) VALUES ('2026-11-25-business-account-erasure') ON CONFLICT DO NOTHING;
COMMIT;
