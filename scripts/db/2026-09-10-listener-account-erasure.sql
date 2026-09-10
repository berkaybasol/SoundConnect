-- Apply after the Overthinking inbox, profile-share and production-safety migrations.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE tbl_user ADD COLUMN IF NOT EXISTS erased_at timestamp;
CREATE INDEX IF NOT EXISTS idx_user_erased ON tbl_user(id) WHERE erased_at IS NOT NULL;

CREATE OR REPLACE FUNCTION soundconnect_preserve_erased_user() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.erased_at IS NOT NULL AND (to_jsonb(NEW) - 'updated_at') IS DISTINCT FROM (to_jsonb(OLD) - 'updated_at') THEN
        RAISE EXCEPTION 'Account was permanently erased' USING ERRCODE = '23514', CONSTRAINT = 'ck_account_erased';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS tr_preserve_erased_user ON tbl_user;
CREATE TRIGGER tr_preserve_erased_user BEFORE UPDATE ON tbl_user
FOR EACH ROW EXECUTE FUNCTION soundconnect_preserve_erased_user();

-- Every referenced account is locked in a stable order. A request authenticated
-- before deletion either commits before erasure or is rejected after it; stale
-- application entities cannot recreate profile data after the cleanup commit.
CREATE OR REPLACE FUNCTION soundconnect_reject_erased_reference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE account_id uuid; erased timestamp;
BEGIN
    IF TG_OP = 'UPDATE' AND NOT EXISTS (
        SELECT 1 FROM unnest(TG_ARGV) AS column_name
        WHERE to_jsonb(NEW)->column_name IS DISTINCT FROM to_jsonb(OLD)->column_name
    ) THEN RETURN NEW; END IF;
    FOR account_id IN SELECT DISTINCT (to_jsonb(NEW)->>column_name)::uuid
                      FROM unnest(TG_ARGV) AS column_name
                      WHERE to_jsonb(NEW)->>column_name IS NOT NULL ORDER BY 1 LOOP
        SELECT erased_at INTO erased FROM tbl_user WHERE id = account_id FOR SHARE;
        IF erased IS NOT NULL THEN
            RAISE EXCEPTION 'Account was permanently erased' USING ERRCODE = '23514', CONSTRAINT = 'ck_account_erased';
        END IF;
    END LOOP;
    RETURN NEW;
END $$;

DO $$
DECLARE item record; arguments text; columns_sql text;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('tbl_listener-profile', ARRAY['user_id']),
        ('tbl_overthinking_post', ARRAY['author_id']),
        ('tbl_overthinking_reveal_request', ARRAY['author_id','requester_id']),
        ('tbl_overthinking_profile_share', ARRAY['owner_user_id']),
        ('tbl_event_audience_intent', ARRAY['user_id']),
        ('tbl_comment', ARRAY['user_id']),
        ('tbl_like', ARRAY['user_id']),
        ('tbl_follow', ARRAY['follower_id','following_id']),
        ('tbl_band_follow', ARRAY['follower_id']),
        ('tbl_table_group', ARRAY['owner_id']),
        ('tbl_table_group_participants', ARRAY['user_id']),
        ('tbl_table_group_message', ARRAY['sender_id']),
        ('tbl_table_group_game_player', ARRAY['user_id']),
        ('tbl_studio_room_reservation', ARRAY['requester_id']),
        ('tbl_dm_message', ARRAY['sender_id','recipient_id']),
        ('tbl_dm_conversation', ARRAY['user_a_id','user_b_id']),
        ('user_roles', ARRAY['user_id']),
        ('user_permissions', ARRAY['user_id'])
    ) AS references_to_guard(table_name, user_columns) LOOP
        SELECT string_agg(quote_literal(value), ', '), string_agg(quote_ident(value), ', ')
        INTO arguments, columns_sql FROM unnest(item.user_columns) value;
        EXECUTE format('DROP TRIGGER IF EXISTS tr_reject_erased_reference ON %I', item.table_name);
        EXECUTE format('CREATE TRIGGER tr_reject_erased_reference BEFORE INSERT OR UPDATE OF %s ON %I FOR EACH ROW EXECUTE FUNCTION soundconnect_reject_erased_reference(%s)', columns_sql, item.table_name, arguments);
    END LOOP;
END $$;

CREATE OR REPLACE FUNCTION soundconnect_guard_listener_media_owner() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE account_id uuid; erased timestamp;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.owner_type = OLD.owner_type AND NEW.owner_id = OLD.owner_id THEN
        RETURN NEW;
    END IF;
    IF NEW.owner_type = 'USER' THEN
        account_id := NEW.owner_id;
    ELSIF NEW.owner_type = 'LISTENER_PROFILE' THEN
        SELECT user_id INTO account_id FROM "tbl_listener-profile" WHERE id = NEW.owner_id;
        IF account_id IS NULL THEN
            RAISE EXCEPTION 'Listener owner no longer exists' USING ERRCODE = '23514', CONSTRAINT = 'ck_account_erased';
        END IF;
    ELSE RETURN NEW;
    END IF;
    SELECT erased_at INTO erased FROM tbl_user WHERE id = account_id FOR SHARE;
    IF erased IS NOT NULL THEN
        RAISE EXCEPTION 'Account was permanently erased' USING ERRCODE = '23514', CONSTRAINT = 'ck_account_erased';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS tr_listener_media_owner ON tbl_media_asset;
CREATE TRIGGER tr_listener_media_owner BEFORE INSERT OR UPDATE OF owner_type, owner_id ON tbl_media_asset
FOR EACH ROW EXECUTE FUNCTION soundconnect_guard_listener_media_owner();

CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO soundconnect_schema_migrations(migration_id) VALUES ('2026-09-10-listener-account-erasure') ON CONFLICT DO NOTHING;
COMMIT;
