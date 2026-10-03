-- Additive, replay-safe. Keep original outbox evidence after its normal retention.
-- The INSERT trigger also captures events from the unchanged legacy expiry worker.
BEGIN;
ALTER TABLE tbl_table_group_participants ADD COLUMN IF NOT EXISTS application_id uuid;

CREATE TABLE IF NOT EXISTS tbl_table_notification_event (
    event_id uuid PRIMARY KEY,
    recipient_id uuid NOT NULL,
    notification_type varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL
);

CREATE OR REPLACE FUNCTION capture_table_notification_event() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, public AS $$
BEGIN
    INSERT INTO public.tbl_table_notification_event(event_id,recipient_id,notification_type,payload,occurred_at)
    VALUES (NEW.event_id,NEW.recipient_id,NEW.notification_type,NEW.payload,NEW.occurred_at)
    ON CONFLICT (event_id) DO NOTHING;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_table_notification_event ON tbl_table_group_notification_outbox;
CREATE TRIGGER trg_table_notification_event AFTER INSERT ON tbl_table_group_notification_outbox
FOR EACH ROW EXECUTE FUNCTION capture_table_notification_event();

-- Only surviving authoritative source events can prove legacy notification history.
-- Never infer an old application's identity from timestamps or current membership.
INSERT INTO tbl_table_notification_event(event_id,recipient_id,notification_type,payload,occurred_at)
SELECT event_id,recipient_id,notification_type,payload,occurred_at
FROM tbl_table_group_notification_outbox ON CONFLICT (event_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-30-table-notification-target') ON CONFLICT (migration_id) DO NOTHING;
COMMIT;
