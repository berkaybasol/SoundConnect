-- Forward redaction for existing MEDIA LIKE/COMMENT only. Never infer an actor from a name.
-- Run before the new binary. The trigger also sanitizes old producers during that transition.
BEGIN;
SET LOCAL lock_timeout='10s';
SET LOCAL statement_timeout='60s';
CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE OR REPLACE FUNCTION soundconnect_media_notification_payload(value jsonb)
RETURNS jsonb LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE result jsonb;
BEGIN
    result := value - ARRAY['actorName','actorUsername','actorAvatarUrl','actorProfilePictureUrl',
        'actorVisibilityMode','senderName','senderUsername','senderAvatarUrl','senderVisibilityMode',
        'username','avatarUrl','profilePictureUrl','displayName','profileName'];
    IF jsonb_typeof(value->'mediaIdentityVersion')='number' AND value->>'mediaIdentityVersion'='1'
        AND jsonb_typeof(value->'actorId')='string'
        AND value->>'actorId' ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
        RETURN result || jsonb_build_object('actorId',lower(value->>'actorId'),'mediaIdentityVersion',1);
    END IF;
    RETURN (result - 'actorId') || jsonb_build_object('mediaIdentityVersion',0);
END $$;

CREATE OR REPLACE FUNCTION soundconnect_sanitize_media_notification()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.type IN ('SOCIAL_LIKE','SOCIAL_COMMENT') AND NEW.payload->>'targetType'='MEDIA' THEN
        NEW.title := CASE NEW.type WHEN 'SOCIAL_LIKE' THEN 'Bir kullanıcı içeriğini beğendi'
            ELSE 'Bir kullanıcı içeriğine yorum yaptı' END;
        NEW.message := 'Bildirime dokunarak içeriği açabilirsin.';
        NEW.payload := soundconnect_media_notification_payload(NEW.payload);
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS trg_media_notification_identity ON tbl_notification;
CREATE TRIGGER trg_media_notification_identity BEFORE INSERT OR UPDATE OF title,message,payload
    ON tbl_notification FOR EACH ROW EXECUTE FUNCTION soundconnect_sanitize_media_notification();

UPDATE tbl_notification SET
    title=CASE type WHEN 'SOCIAL_LIKE' THEN 'Bir kullanıcı içeriğini beğendi' ELSE 'Bir kullanıcı içeriğine yorum yaptı' END,
    message='Bildirime dokunarak içeriği açabilirsin.',
    payload=soundconnect_media_notification_payload(payload)
WHERE type IN ('SOCIAL_LIKE','SOCIAL_COMMENT') AND payload->>'targetType'='MEDIA'
  AND (title IS DISTINCT FROM CASE type WHEN 'SOCIAL_LIKE' THEN 'Bir kullanıcı içeriğini beğendi' ELSE 'Bir kullanıcı içeriğine yorum yaptı' END
       OR message IS DISTINCT FROM 'Bildirime dokunarak içeriği açabilirsin.'
       OR payload IS DISTINCT FROM soundconnect_media_notification_payload(payload));

ALTER TABLE tbl_notification DROP CONSTRAINT IF EXISTS ck_media_notification_identity;
ALTER TABLE tbl_notification ADD CONSTRAINT ck_media_notification_identity CHECK (
    NOT coalesce(type IN ('SOCIAL_LIKE','SOCIAL_COMMENT') AND payload->>'targetType'='MEDIA',false)
    OR (title = CASE type WHEN 'SOCIAL_LIKE' THEN 'Bir kullanıcı içeriğini beğendi' ELSE 'Bir kullanıcı içeriğine yorum yaptı' END
        AND message = 'Bildirime dokunarak içeriği açabilirsin.'
        AND payload = soundconnect_media_notification_payload(payload)) IS TRUE
);
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-28-media-notification-identity') ON CONFLICT DO NOTHING;
COMMIT;
