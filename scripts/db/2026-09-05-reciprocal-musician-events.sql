-- Musician-owned events and event-scoped venue consent.
-- Additive/rerunnable. Stop API/event writers before applying. No event or decision is deleted.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE tbl_event ADD COLUMN IF NOT EXISTS event_origin varchar(20);
ALTER TABLE tbl_event ADD COLUMN IF NOT EXISTS organizer_user_id uuid;
ALTER TABLE tbl_event ADD COLUMN IF NOT EXISTS venue_name_snapshot varchar(255);
ALTER TABLE tbl_event ADD COLUMN IF NOT EXISTS venue_approval_status varchar(20);
ALTER TABLE tbl_event ADD COLUMN IF NOT EXISTS venue_calendar_approved boolean;

UPDATE tbl_event SET event_origin = 'VENUE' WHERE event_origin IS NULL;
UPDATE tbl_event event SET organizer_user_id = venue.owner_id
FROM tbl_venues venue WHERE event.venue_id = venue.id AND event.event_origin = 'VENUE' AND event.organizer_user_id IS NULL;
UPDATE tbl_event SET venue_approval_status = 'APPROVED' WHERE venue_approval_status IS NULL AND event_origin = 'VENUE';
UPDATE tbl_event SET venue_calendar_approved = true WHERE venue_calendar_approved IS NULL AND event_origin = 'VENUE';

-- Never guess organizer identity for invalid legacy or partial reciprocal rows.
DO $migration$
BEGIN
    IF EXISTS (SELECT 1 FROM tbl_event event LEFT JOIN tbl_user organizer ON organizer.id = event.organizer_user_id
               WHERE organizer.id IS NULL OR event.venue_approval_status IS NULL OR event.venue_calendar_approved IS NULL) THEN
        RAISE EXCEPTION 'Event organizer/venue consent missing; reconcile legacy ownership before reciprocal rollout';
    END IF;
END
$migration$;

ALTER TABLE tbl_event ALTER COLUMN venue_id DROP NOT NULL;
ALTER TABLE tbl_event ALTER COLUMN event_origin SET DEFAULT 'VENUE';
ALTER TABLE tbl_event ALTER COLUMN event_origin SET NOT NULL;
ALTER TABLE tbl_event ALTER COLUMN organizer_user_id SET NOT NULL;
ALTER TABLE tbl_event ALTER COLUMN venue_approval_status SET DEFAULT 'APPROVED';
ALTER TABLE tbl_event ALTER COLUMN venue_approval_status SET NOT NULL;
ALTER TABLE tbl_event ALTER COLUMN venue_calendar_approved SET DEFAULT true;
ALTER TABLE tbl_event ALTER COLUMN venue_calendar_approved SET NOT NULL;

ALTER TABLE tbl_event DROP CONSTRAINT IF EXISTS ck_event_origin_contract;
ALTER TABLE tbl_event ADD CONSTRAINT ck_event_origin_contract CHECK (
    (event_origin = 'VENUE' AND venue_id IS NOT NULL AND venue_approval_status = 'APPROVED' AND venue_calendar_approved)
    OR (event_origin = 'MUSICIAN' AND musician_profile_id IS NOT NULL AND band_id IS NULL
        AND performer_approval_status = 'APPROVED' AND profile_calendar_approved)
);
ALTER TABLE tbl_event DROP CONSTRAINT IF EXISTS ck_event_venue_consent;
ALTER TABLE tbl_event ADD CONSTRAINT ck_event_venue_consent CHECK (
    (NOT venue_calendar_approved OR (venue_approval_status = 'APPROVED' AND venue_id IS NOT NULL))
    AND ((venue_approval_status = 'APPROVED' AND venue_id IS NOT NULL)
      OR (venue_approval_status = 'NOT_REQUIRED' AND venue_id IS NULL AND venue_name_snapshot IS NULL)
      OR (venue_approval_status IN ('PENDING','REJECTED') AND venue_id IS NULL
          AND venue_name_snapshot IS NOT NULL AND btrim(venue_name_snapshot) <> ''))
);
DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'tbl_event'::regclass AND conname = 'fk_event_organizer_user') THEN
        ALTER TABLE tbl_event ADD CONSTRAINT fk_event_organizer_user FOREIGN KEY (organizer_user_id) REFERENCES tbl_user(id);
    END IF;
END
$migration$;
CREATE INDEX IF NOT EXISTS idx_event_musician_organizer ON tbl_event(organizer_user_id, event_date DESC, start_time DESC, id)
    WHERE event_origin = 'MUSICIAN';
CREATE INDEX IF NOT EXISTS idx_event_published_venue_date ON tbl_event(venue_id, event_date, start_time, id)
    WHERE venue_calendar_approved;

CREATE TABLE IF NOT EXISTS event_venue_requests (
    id uuid PRIMARY KEY,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    event_id uuid NOT NULL,
    venue_id uuid NOT NULL,
    venue_name_snapshot varchar(255) NOT NULL,
    status varchar(20) NOT NULL,
    request_purpose varchar(30) NOT NULL,
    requested_by_user_id uuid NOT NULL,
    decided_by_user_id uuid,
    decided_at timestamp without time zone,
    version bigint NOT NULL DEFAULT 0
);
-- Also reconcile Hibernate-created development constraints to the production contract.
-- CHECK expressions evaluate NULL as unknown, so their required columns must
-- independently be NOT NULL even when this table already existed.
ALTER TABLE event_venue_requests
    ALTER COLUMN id SET NOT NULL,
    ALTER COLUMN event_id SET NOT NULL,
    ALTER COLUMN venue_id SET NOT NULL,
    ALTER COLUMN venue_name_snapshot SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN request_purpose SET NOT NULL,
    ALTER COLUMN requested_by_user_id SET NOT NULL,
    ALTER COLUMN version SET DEFAULT 0,
    ALTER COLUMN version SET NOT NULL;
DO $migration$
DECLARE existing_fk record;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'event_venue_requests'::regclass AND contype = 'p') THEN
        ALTER TABLE event_venue_requests ADD CONSTRAINT pk_event_venue_requests PRIMARY KEY(id);
    END IF;
    -- Hibernate uses generated FK names. Remove only the single-column FKs
    -- whose exact source/target pairs are replaced below, not unrelated keys.
    FOR existing_fk IN
        SELECT constraint_entry.conname
        FROM pg_constraint constraint_entry
        JOIN pg_attribute source_column ON source_column.attrelid = constraint_entry.conrelid
          AND constraint_entry.conkey = ARRAY[source_column.attnum]::smallint[]
        JOIN pg_attribute target_column ON target_column.attrelid = constraint_entry.confrelid
          AND constraint_entry.confkey = ARRAY[target_column.attnum]::smallint[]
        WHERE constraint_entry.conrelid = 'event_venue_requests'::regclass
          AND constraint_entry.contype = 'f' AND target_column.attname = 'id'
          AND ((source_column.attname = 'event_id' AND constraint_entry.confrelid = 'tbl_event'::regclass)
            OR (source_column.attname = 'venue_id' AND constraint_entry.confrelid = 'tbl_venues'::regclass)
            OR (source_column.attname IN ('requested_by_user_id', 'decided_by_user_id')
                AND constraint_entry.confrelid = 'tbl_user'::regclass))
    LOOP
        EXECUTE format('ALTER TABLE event_venue_requests DROP CONSTRAINT %I', existing_fk.conname);
    END LOOP;
END
$migration$;
ALTER TABLE event_venue_requests DROP CONSTRAINT IF EXISTS uk_event_venue_request_event;
ALTER TABLE event_venue_requests ADD CONSTRAINT uk_event_venue_request_event UNIQUE(event_id);
ALTER TABLE event_venue_requests ADD CONSTRAINT fk_event_venue_request_event FOREIGN KEY(event_id) REFERENCES tbl_event(id) ON DELETE CASCADE;
ALTER TABLE event_venue_requests ADD CONSTRAINT fk_event_venue_request_venue FOREIGN KEY(venue_id) REFERENCES tbl_venues(id);
ALTER TABLE event_venue_requests ADD CONSTRAINT fk_event_venue_request_requested_by FOREIGN KEY(requested_by_user_id) REFERENCES tbl_user(id);
ALTER TABLE event_venue_requests ADD CONSTRAINT fk_event_venue_request_decided_by FOREIGN KEY(decided_by_user_id) REFERENCES tbl_user(id);
ALTER TABLE event_venue_requests DROP CONSTRAINT IF EXISTS ck_event_venue_request_contract;
ALTER TABLE event_venue_requests ADD CONSTRAINT ck_event_venue_request_contract CHECK (
    btrim(venue_name_snapshot) <> '' AND request_purpose IN ('VENUE_CONSENT','PROFILE_VISIBILITY')
    AND ((status = 'PENDING' AND decided_by_user_id IS NULL AND decided_at IS NULL)
      OR (status IN ('ACCEPTED','REJECTED') AND decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL))
    AND version >= 0
);
CREATE INDEX IF NOT EXISTS idx_event_venue_request_venue_status ON event_venue_requests(venue_id, status, created_at DESC);

-- Creation retry keys survive deletion of the resulting event. The event_id is
-- intentionally not a foreign key: deleting an event must not let an old
-- timed-out/retried operation recreate it or send another approval request.
CREATE TABLE IF NOT EXISTS musician_event_creations (
    id uuid PRIMARY KEY,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    organizer_user_id uuid NOT NULL,
    client_request_id uuid NOT NULL,
    payload_hash varchar(64) NOT NULL,
    event_id uuid NOT NULL
);
ALTER TABLE musician_event_creations
    ALTER COLUMN id SET NOT NULL,
    ALTER COLUMN organizer_user_id SET NOT NULL,
    ALTER COLUMN client_request_id SET NOT NULL,
    ALTER COLUMN payload_hash SET NOT NULL,
    ALTER COLUMN event_id SET NOT NULL;
DO $migration$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'musician_event_creations'::regclass AND contype = 'p') THEN
        ALTER TABLE musician_event_creations ADD CONSTRAINT pk_musician_event_creations PRIMARY KEY(id);
    END IF;
END
$migration$;
ALTER TABLE musician_event_creations DROP CONSTRAINT IF EXISTS uk_musician_event_creation_client;
ALTER TABLE musician_event_creations ADD CONSTRAINT uk_musician_event_creation_client UNIQUE(organizer_user_id, client_request_id);
ALTER TABLE musician_event_creations DROP CONSTRAINT IF EXISTS uk_musician_event_creation_event;
ALTER TABLE musician_event_creations ADD CONSTRAINT uk_musician_event_creation_event UNIQUE(event_id);
ALTER TABLE musician_event_creations DROP CONSTRAINT IF EXISTS fk_musician_event_creation_organizer;
ALTER TABLE musician_event_creations ADD CONSTRAINT fk_musician_event_creation_organizer FOREIGN KEY(organizer_user_id) REFERENCES tbl_user(id);
ALTER TABLE musician_event_creations DROP CONSTRAINT IF EXISTS ck_musician_event_creation_payload_hash;
ALTER TABLE musician_event_creations ADD CONSTRAINT ck_musician_event_creation_payload_hash CHECK(payload_hash ~ '^[0-9a-f]{64}$');

-- Hibernate-generated enum CHECK names vary by schema history. Extend only
-- single-column enum checks, preserving every pre-existing notification value
-- and validation state; neither notification data nor unrelated checks change.
DO $migration$
DECLARE target record;
BEGIN
    FOR target IN
        SELECT relation.relname AS table_name, constraint_entry.conname,
               constraint_entry.convalidated, column_entry.attname AS column_name,
               pg_get_expr(constraint_entry.conbin, constraint_entry.conrelid) AS expression
        FROM pg_constraint constraint_entry
        JOIN pg_class relation ON relation.oid = constraint_entry.conrelid
        JOIN pg_namespace namespace_entry ON namespace_entry.oid = relation.relnamespace
        JOIN pg_attribute column_entry ON column_entry.attrelid = relation.oid
          AND column_entry.attname = CASE relation.relname WHEN 'tbl_notification' THEN 'type' ELSE 'notification_type' END
        WHERE namespace_entry.nspname = current_schema()
          AND relation.relname IN ('tbl_notification', 'tbl_event_performer_notification_outbox')
          AND constraint_entry.contype = 'c'
          AND constraint_entry.conkey = ARRAY[column_entry.attnum]::smallint[]
          -- A compound CHECK must never be relaxed by appending OR(new types).
          -- Only accept PostgreSQL's canonical direct varchar/text IN-list:
          -- ((column)::text = ANY ((ARRAY['VALUE'::varchar, ...])::text[]))
          -- or (column = ANY (ARRAY['VALUE'::text, ...])).
          AND pg_get_expr(constraint_entry.conbin, constraint_entry.conrelid) ~
              ('^\(\(?' || column_entry.attname || '(\)::text)? = ANY \(\(?ARRAY\[.*\](\)::text\[\])?\)\)$')
          AND pg_get_expr(constraint_entry.conbin, constraint_entry.conrelid) NOT LIKE '% AND %'
          AND pg_get_expr(constraint_entry.conbin, constraint_entry.conrelid) NOT LIKE '% OR %'
          AND NOT (pg_get_expr(constraint_entry.conbin, constraint_entry.conrelid) LIKE '%EVENT_VENUE_APPROVAL_REQUESTED%'
                   AND pg_get_expr(constraint_entry.conbin, constraint_entry.conrelid) LIKE '%EVENT_VENUE_APPROVED%'
                   AND pg_get_expr(constraint_entry.conbin, constraint_entry.conrelid) LIKE '%EVENT_VENUE_REJECTED%')
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', target.table_name, target.conname);
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK ((%s) OR %I IN (%L, %L, %L)) NOT VALID',
            target.table_name, target.conname, target.expression, target.column_name,
            'EVENT_VENUE_APPROVAL_REQUESTED', 'EVENT_VENUE_APPROVED', 'EVENT_VENUE_REJECTED');
        IF target.convalidated THEN
            EXECUTE format('ALTER TABLE %I VALIDATE CONSTRAINT %I', target.table_name, target.conname);
        END IF;
    END LOOP;
END
$migration$;

COMMIT;
