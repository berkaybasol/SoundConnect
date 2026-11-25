BEGIN;
SET LOCAL lock_timeout = '10s';

ALTER TABLE tbl_table_group
    ADD COLUMN IF NOT EXISTS report_closed_at timestamptz,
    ADD COLUMN IF NOT EXISTS report_removed_at timestamptz;

-- Cancellation used to notify accepted members other than the owner. Only an
-- actual retained cancellation can date a pre-migration cancelled table.
UPDATE tbl_table_group t
SET report_closed_at = e.ended_at
FROM (
    SELECT payload->>'tableGroupId' AS table_id, min(occurred_at) AS ended_at
    FROM tbl_table_notification_event
    WHERE notification_type='TABLE_CANCELLED' AND payload->>'action'='CANCELLED'
        AND occurred_at > now() - interval '48 hours'
    GROUP BY payload->>'tableGroupId'
) e
WHERE t.id::text=e.table_id AND t.status='CANCELLED' AND t.report_closed_at IS NULL;

CREATE OR REPLACE FUNCTION soundconnect_report_table_closed() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status='ACTIVE' AND NEW.status='CANCELLED' THEN
        NEW.report_closed_at := least(clock_timestamp(), OLD.expires_at);
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS tr_report_table_closed ON tbl_table_group;
CREATE TRIGGER tr_report_table_closed BEFORE UPDATE OF status ON tbl_table_group
    FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)
    EXECUTE FUNCTION soundconnect_report_table_closed();

CREATE INDEX IF NOT EXISTS idx_table_report_left_event
    ON tbl_table_notification_event ((payload->>'leaverId'), occurred_at DESC)
    WHERE notification_type='TABLE_PARTICIPANT_LEFT';
CREATE INDEX IF NOT EXISTS idx_table_report_removed_event
    ON tbl_table_notification_event (recipient_id, occurred_at DESC)
    WHERE notification_type='TABLE_REMOVED';
CREATE INDEX IF NOT EXISTS idx_table_report_participant
    ON tbl_table_group_participants (user_id, status, table_group_id);

CREATE TABLE IF NOT EXISTS tbl_table_group_report (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    reporter_id uuid,
    reporter_username varchar(255),
    table_group_id uuid NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('LISTING','MESSAGE','INCIDENT')),
    reason varchar(32) NOT NULL CHECK (reason IN ('INAPPROPRIATE','MISLEADING','MONEY_REQUEST','HARASSMENT','SPAM','SAFETY','OTHER')),
    description varchar(500),
    reported_at timestamptz NOT NULL,
    table_description text,
    venue_name varchar(128),
    city_name text,
    district_name text,
    meeting_at timestamptz,
    owner_id uuid,
    owner_username varchar(255),
    message_id uuid,
    message_text text,
    sender_id uuid,
    sender_username varchar(255),
    message_sent_at timestamptz,
    membership_end_reason varchar(16),
    membership_ended_at timestamptz,
    status varchar(32) NOT NULL DEFAULT 'OPEN',
    decision varchar(32),
    resolution_note varchar(500),
    reviewer_id uuid,
    reviewer_username varchar(255),
    reviewed_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uk_table_report_request UNIQUE (reporter_id, request_id),
    CONSTRAINT ck_table_report_decision CHECK (
        (status='OPEN' AND decision IS NULL AND reviewed_at IS NULL AND resolution_note IS NULL) OR
        (status='RESOLVED' AND decision IS NOT NULL AND decision IN ('DISMISS','CONFIRM_VIOLATION','REMOVE_MESSAGE','REMOVE_TABLE')
            AND reviewed_at IS NOT NULL AND resolution_note IS NOT NULL)),
    CONSTRAINT ck_table_report_message CHECK (
        (kind='MESSAGE') = (message_id IS NOT NULL) AND
        (kind='MESSAGE') = (message_text IS NOT NULL) AND
        (kind='MESSAGE') = (message_sent_at IS NOT NULL)),
    CONSTRAINT ck_table_report_membership_end CHECK (
        (membership_end_reason IS NULL) = (membership_ended_at IS NULL) AND
        (membership_end_reason IS NULL OR (kind='INCIDENT' AND membership_end_reason IN ('LEFT','KICKED','CLOSED','EXPIRED'))))
);
CREATE INDEX IF NOT EXISTS idx_table_report_queue ON tbl_table_group_report(status, reported_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_table_report_owner ON tbl_table_group_report(owner_id) WHERE owner_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_table_report_sender ON tbl_table_group_report(sender_id) WHERE sender_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_table_report_reviewer ON tbl_table_group_report(reviewer_id) WHERE reviewer_id IS NOT NULL;

-- The deliberately selected report evidence has its own lifecycle. Erasing an
-- account unlinks its identity from the staff-only record, including its username.
CREATE OR REPLACE FUNCTION soundconnect_anonymize_table_reports() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    UPDATE tbl_table_group_report SET reporter_id=NULL, reporter_username=NULL WHERE reporter_id=NEW.id;
    UPDATE tbl_table_group_report SET owner_id=NULL, owner_username=NULL WHERE owner_id=NEW.id;
    UPDATE tbl_table_group_report SET sender_id=NULL, sender_username=NULL WHERE sender_id=NEW.id;
    UPDATE tbl_table_group_report SET reviewer_id=NULL, reviewer_username=NULL WHERE reviewer_id=NEW.id;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS tr_anonymize_table_reports ON tbl_user;
CREATE TRIGGER tr_anonymize_table_reports AFTER UPDATE OF erased_at ON tbl_user
    FOR EACH ROW WHEN (OLD.erased_at IS NULL AND NEW.erased_at IS NOT NULL)
    EXECUTE FUNCTION soundconnect_anonymize_table_reports();
COMMIT;
