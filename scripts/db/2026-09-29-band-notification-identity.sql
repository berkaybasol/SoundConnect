-- Forward-only BAND redaction. No name parsing, actor inference, receipt/read/route regeneration.
-- Stop old writers, apply this script, then start the coordinated candidate API.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now()
);
CREATE OR REPLACE FUNCTION soundconnect_band_notification_payload(kind text, value jsonb)
RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE result jsonb := '{"module":"BAND","bandIdentityVersion":0}'::jsonb;
        key text; field text; expected_action text := substring(kind from 6);
        uuid_pattern text := '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$';
BEGIN
    IF jsonb_typeof(value) IS DISTINCT FROM 'object' THEN RETURN result; END IF;
    FOREACH field IN ARRAY ARRAY['bandId','invitationId'] LOOP
        IF jsonb_typeof(value->field)='string' AND value->>field ~* uuid_pattern THEN
            result := result || jsonb_build_object(field,lower(value->>field));
        END IF;
    END LOOP;
    IF value->>'action'=expected_action AND jsonb_typeof(value->'action')='string' THEN
        result := result || jsonb_build_object('action',expected_action);
    END IF;
    key := CASE kind WHEN 'BAND_INVITE_RECEIVED' THEN 'inviterId'
        WHEN 'BAND_MEMBER_REMOVED' THEN 'requesterId' ELSE 'memberId' END;
    IF jsonb_typeof(value->'bandIdentityVersion')='number' AND value->>'bandIdentityVersion'='1'
        AND value->>'module'='BAND' AND value->>'action'=expected_action AND result ? 'bandId'
        AND jsonb_typeof(value->key)='string' AND value->>key ~* uuid_pattern THEN
        result := result || jsonb_build_object('bandIdentityVersion',1,key,lower(value->>key));
    END IF;
    RETURN result;
END $$;
CREATE OR REPLACE FUNCTION soundconnect_band_notification_title(kind text)
RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE kind
        WHEN 'BAND_INVITE_RECEIVED' THEN 'Yeni grup daveti'
        WHEN 'BAND_INVITE_ACCEPTED' THEN 'Grup daveti kabul edildi'
        WHEN 'BAND_INVITE_REJECTED' THEN 'Grup daveti reddedildi'
        WHEN 'BAND_MEMBER_REMOVED' THEN 'Grup üyeliğin sonlandırıldı'
        WHEN 'BAND_MEMBER_LEFT' THEN 'Bir üye gruptan ayrıldı' END
$$;
CREATE OR REPLACE FUNCTION soundconnect_sanitize_band_notification()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.type IN ('BAND_INVITE_RECEIVED','BAND_INVITE_ACCEPTED','BAND_INVITE_REJECTED','BAND_MEMBER_REMOVED','BAND_MEMBER_LEFT') THEN
        NEW.title := soundconnect_band_notification_title(NEW.type);
        NEW.message := 'Grup bildiriminin ayrıntılarını uygulamada görebilirsin.';
        NEW.payload := soundconnect_band_notification_payload(NEW.type,NEW.payload);
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS trg_band_notification_identity ON tbl_notification;
CREATE TRIGGER trg_band_notification_identity BEFORE INSERT OR UPDATE OF type,title,message,payload
    ON tbl_notification FOR EACH ROW EXECUTE FUNCTION soundconnect_sanitize_band_notification();

UPDATE tbl_notification SET title=soundconnect_band_notification_title(type),
    message='Grup bildiriminin ayrıntılarını uygulamada görebilirsin.',
    payload=soundconnect_band_notification_payload(type,payload)
WHERE type IN ('BAND_INVITE_RECEIVED','BAND_INVITE_ACCEPTED','BAND_INVITE_REJECTED','BAND_MEMBER_REMOVED','BAND_MEMBER_LEFT')
  AND (title IS DISTINCT FROM soundconnect_band_notification_title(type)
    OR message IS DISTINCT FROM 'Grup bildiriminin ayrıntılarını uygulamada görebilirsin.'
    OR payload IS DISTINCT FROM soundconnect_band_notification_payload(type,payload));
ALTER TABLE tbl_notification DROP CONSTRAINT IF EXISTS ck_band_notification_identity;
ALTER TABLE tbl_notification ADD CONSTRAINT ck_band_notification_identity CHECK (
    type NOT IN ('BAND_INVITE_RECEIVED','BAND_INVITE_ACCEPTED','BAND_INVITE_REJECTED','BAND_MEMBER_REMOVED','BAND_MEMBER_LEFT')
    OR (title=soundconnect_band_notification_title(type)
        AND message='Grup bildiriminin ayrıntılarını uygulamada görebilirsin.'
        AND payload=soundconnect_band_notification_payload(type,payload)) IS TRUE
);
INSERT INTO soundconnect_schema_migrations(migration_id) VALUES ('2026-09-29-band-notification-identity') ON CONFLICT DO NOTHING;
COMMIT;
