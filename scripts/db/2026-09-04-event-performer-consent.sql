-- Event-scoped performer consent rollout.
--
-- Run with psql -v ON_ERROR_STOP=1 while event and band/profile writers are
-- stopped. The script is additive and rerunnable. Production uses
-- hibernate.ddl-auto=validate, so this must complete before the consent-aware
-- application binary starts.

BEGIN;

DO $migration$
BEGIN
    IF to_regclass('tbl_event') IS NULL
       OR to_regclass('tbl_musician_profile') IS NULL
       OR to_regclass('tbl_band') IS NULL
       OR to_regclass('tbl_user') IS NULL
       OR to_regclass('tbl_venues') IS NULL
       OR to_regclass('tbl_band_member') IS NULL
       OR to_regclass('musician_profile_venues') IS NULL
       OR to_regclass('venue_active_bands') IS NULL THEN
        RAISE EXCEPTION 'Event performer consent migration requires event, venue, performer, user, and active-connection tables';
    END IF;
END
$migration$;

ALTER TABLE tbl_event
    ADD COLUMN IF NOT EXISTS performer_approval_status varchar(20);

ALTER TABLE tbl_event
    ALTER COLUMN manual_performer_name TYPE varchar(120);

CREATE TABLE IF NOT EXISTS event_performer_requests (
    id uuid,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    event_id uuid,
    musician_profile_id uuid,
    band_id uuid,
    performer_name_snapshot varchar(100),
    status varchar(20),
    requested_by_user_id uuid,
    decided_by_user_id uuid,
    decided_at timestamp without time zone,
    version bigint
);

-- Reconcile a development schema that Hibernate may have created before this
-- migration was applied.
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS id uuid;
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS created_at timestamp without time zone;
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS updated_at timestamp without time zone;
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS event_id uuid;
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS musician_profile_id uuid;
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS band_id uuid;
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS performer_name_snapshot varchar(100);
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS status varchar(20);
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS requested_by_user_id uuid;
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS decided_by_user_id uuid;
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS decided_at timestamp without time zone;
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS version bigint;

UPDATE event_performer_requests SET version = 0 WHERE version IS NULL;

-- A partial development rollout must not be guessed through. Every row with a
-- null event status is legacy input and therefore must not already have a
-- consent request.
DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM tbl_event event
        WHERE event.performer_approval_status IS NULL
          AND event.musician_profile_id IS NOT NULL
          AND event.band_id IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'Legacy event has both musician and band targets';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM event_performer_requests request
        JOIN tbl_event event ON event.id = request.event_id
        WHERE event.performer_approval_status IS NULL
    ) THEN
        RAISE EXCEPTION 'Partial event performer consent rollout detected';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tbl_event event
        LEFT JOIN tbl_venues venue ON venue.id = event.venue_id
        WHERE event.performer_approval_status IS NULL
          AND (event.musician_profile_id IS NOT NULL OR event.band_id IS NOT NULL)
          AND NOT (
              (event.musician_profile_id IS NOT NULL AND EXISTS (
                  SELECT 1
                  FROM musician_profile_venues connection
                  WHERE connection.musician_profile_id = event.musician_profile_id
                    AND connection.venue_id = event.venue_id
              ))
              OR
              (event.band_id IS NOT NULL AND EXISTS (
                  SELECT 1
                  FROM venue_active_bands connection
                  WHERE connection.band_id = event.band_id
                    AND connection.venue_id = event.venue_id
              ))
          )
          AND (venue.id IS NULL OR venue.owner_id IS NULL)
    ) THEN
        RAISE EXCEPTION 'Unconnected legacy performer event has no venue owner to receive the approval decision';
    END IF;
END
$migration$;

-- Capture unconnected legacy links before clearing their public target IDs.
-- Those links were created before consent existed and must never be silently
-- grandfathered as approved.
CREATE TEMP TABLE event_performer_legacy_unconnected ON COMMIT DROP AS
SELECT
    event.id AS event_id,
    event.musician_profile_id,
    event.band_id,
    venue.owner_id AS requested_by_user_id,
    CASE
        WHEN event.band_id IS NOT NULL THEN
            coalesce(nullif(btrim(band.name), ''), 'Grup')
        ELSE
            coalesce(
                nullif(btrim(app_user.user_name), ''),
                nullif(btrim(musician.stage_name), ''),
                nullif(btrim(musician.name), ''),
                'Sanatçı'
            )
    END AS performer_name_snapshot
FROM tbl_event event
JOIN tbl_venues venue ON venue.id = event.venue_id
LEFT JOIN tbl_musician_profile musician ON musician.id = event.musician_profile_id
LEFT JOIN tbl_user app_user ON app_user.id = musician.user_id
LEFT JOIN tbl_band band ON band.id = event.band_id
WHERE event.performer_approval_status IS NULL
  AND ((event.musician_profile_id IS NOT NULL AND event.band_id IS NULL)
       OR (event.musician_profile_id IS NULL AND event.band_id IS NOT NULL))
  AND NOT (
      (event.musician_profile_id IS NOT NULL AND EXISTS (
          SELECT 1
          FROM musician_profile_venues connection
          WHERE connection.musician_profile_id = event.musician_profile_id
            AND connection.venue_id = event.venue_id
      ))
      OR
      (event.band_id IS NOT NULL AND EXISTS (
          SELECT 1
          FROM venue_active_bands connection
          WHERE connection.band_id = event.band_id
            AND connection.venue_id = event.venue_id
      ))
  );

-- A pending request must always have at least one principal who is authorized
-- to decide it. Do not turn inconsistent legacy band data into an approval
-- request that can never leave PENDING.
DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM event_performer_legacy_unconnected legacy
        WHERE char_length(legacy.performer_name_snapshot) > 100
    ) THEN
        RAISE EXCEPTION 'Unconnected legacy performer name exceeds the 100 character consent snapshot limit';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM event_performer_legacy_unconnected legacy
        WHERE legacy.band_id IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM tbl_band_member member
              WHERE member.band_id = legacy.band_id
                AND member.status = 'ACTIVE'
                AND member.band_role = 'FOUNDER'
          )
    ) THEN
        RAISE EXCEPTION 'Unconnected legacy band event has no active founder to decide performer approval';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM event_performer_legacy_unconnected legacy
        JOIN tbl_musician_profile musician ON musician.id = legacy.musician_profile_id
        WHERE legacy.musician_profile_id IS NOT NULL
          AND musician.user_id IS NULL
    ) THEN
        RAISE EXCEPTION 'Unconnected legacy musician event has no profile owner to decide performer approval';
    END IF;
END
$migration$;

INSERT INTO event_performer_requests(
    id, created_at, updated_at, event_id, musician_profile_id, band_id,
    performer_name_snapshot, status, requested_by_user_id,
    decided_by_user_id, decided_at, version
)
SELECT
    md5('soundconnect:event-performer-consent:' || legacy.event_id::text)::uuid,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP,
    legacy.event_id,
    legacy.musician_profile_id,
    legacy.band_id,
    legacy.performer_name_snapshot,
    'PENDING',
    legacy.requested_by_user_id,
    NULL,
    NULL,
    0
FROM event_performer_legacy_unconnected legacy;

UPDATE tbl_event event
SET musician_profile_id = NULL,
    band_id = NULL,
    manual_performer_name = legacy.performer_name_snapshot,
    performer_approval_status = 'PENDING'
FROM event_performer_legacy_unconnected legacy
WHERE event.id = legacy.event_id;

-- Only a real active ArtistVenueConnection may authorize a legacy public link.
UPDATE tbl_event event
SET performer_approval_status = 'APPROVED',
    manual_performer_name = NULL
WHERE event.performer_approval_status IS NULL
  AND (
      (event.musician_profile_id IS NOT NULL AND event.band_id IS NULL AND EXISTS (
          SELECT 1
          FROM musician_profile_venues connection
          WHERE connection.musician_profile_id = event.musician_profile_id
            AND connection.venue_id = event.venue_id
      ))
      OR
      (event.musician_profile_id IS NULL AND event.band_id IS NOT NULL AND EXISTS (
          SELECT 1
          FROM venue_active_bands connection
          WHERE connection.band_id = event.band_id
            AND connection.venue_id = event.venue_id
      ))
  );

UPDATE tbl_event
SET performer_approval_status = 'NOT_REQUIRED'
WHERE performer_approval_status IS NULL
  AND musician_profile_id IS NULL
  AND band_id IS NULL;

DO $migration$
BEGIN
    IF EXISTS (SELECT 1 FROM tbl_event WHERE performer_approval_status IS NULL) THEN
        RAISE EXCEPTION 'Legacy event performer state could not be classified safely';
    END IF;
END
$migration$;

ALTER TABLE tbl_event
    ALTER COLUMN performer_approval_status SET DEFAULT 'NOT_REQUIRED';
ALTER TABLE tbl_event
    ALTER COLUMN performer_approval_status SET NOT NULL;

ALTER TABLE tbl_event
    DROP CONSTRAINT IF EXISTS ck_event_performer_approval_status;
ALTER TABLE tbl_event
    ADD CONSTRAINT ck_event_performer_approval_status
    CHECK (performer_approval_status IN ('NOT_REQUIRED', 'PENDING', 'APPROVED', 'REJECTED')) NOT VALID;
ALTER TABLE tbl_event
    VALIDATE CONSTRAINT ck_event_performer_approval_status;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM tbl_event event
        WHERE (event.performer_approval_status = 'APPROVED'
               AND (((event.musician_profile_id IS NOT NULL) = (event.band_id IS NOT NULL))
                    OR event.manual_performer_name IS NOT NULL))
           OR (event.performer_approval_status IN ('PENDING', 'REJECTED')
               AND (event.musician_profile_id IS NOT NULL
                    OR event.band_id IS NOT NULL
                    OR event.manual_performer_name IS NULL
                    OR btrim(event.manual_performer_name) = ''))
           OR (event.performer_approval_status = 'NOT_REQUIRED'
               AND (event.musician_profile_id IS NOT NULL OR event.band_id IS NOT NULL))
    ) THEN
        RAISE EXCEPTION 'Existing event performer linkage violates the consent fence';
    END IF;
END
$migration$;

ALTER TABLE tbl_event DROP CONSTRAINT IF EXISTS ck_event_performer_consent_link;
ALTER TABLE tbl_event
    ADD CONSTRAINT ck_event_performer_consent_link CHECK (
        (performer_approval_status = 'APPROVED'
         AND ((musician_profile_id IS NOT NULL AND band_id IS NULL)
              OR (musician_profile_id IS NULL AND band_id IS NOT NULL))
         AND manual_performer_name IS NULL)
        OR
        (performer_approval_status = 'NOT_REQUIRED'
         AND musician_profile_id IS NULL AND band_id IS NULL)
        OR
        (performer_approval_status IN ('PENDING', 'REJECTED')
         AND musician_profile_id IS NULL AND band_id IS NULL
         AND manual_performer_name IS NOT NULL
         AND btrim(manual_performer_name) <> '')
    ) NOT VALID;
ALTER TABLE tbl_event VALIDATE CONSTRAINT ck_event_performer_consent_link;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM event_performer_requests request
        WHERE request.id IS NULL
           OR request.event_id IS NULL
           OR request.performer_name_snapshot IS NULL
           OR btrim(request.performer_name_snapshot) = ''
           OR request.status NOT IN ('PENDING', 'ACCEPTED', 'REJECTED')
           OR request.requested_by_user_id IS NULL
           OR request.version < 0
           OR (request.status = 'PENDING'
               AND (request.decided_by_user_id IS NOT NULL OR request.decided_at IS NOT NULL))
           OR (request.status IN ('ACCEPTED', 'REJECTED')
               AND (request.decided_by_user_id IS NULL OR request.decided_at IS NULL))
           OR ((request.musician_profile_id IS NULL) = (request.band_id IS NULL))
           OR NOT EXISTS (SELECT 1 FROM tbl_event event WHERE event.id = request.event_id)
           OR (request.musician_profile_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM tbl_musician_profile musician
                WHERE musician.id = request.musician_profile_id
           ))
           OR (request.band_id IS NOT NULL AND NOT EXISTS (
                SELECT 1 FROM tbl_band band
                WHERE band.id = request.band_id
           ))
    ) THEN
        RAISE EXCEPTION 'Existing event performer request data violates the canonical contract';
    END IF;

    IF EXISTS (
        SELECT event_id FROM event_performer_requests
        GROUP BY event_id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'An event may have at most one performer approval request';
    END IF;
END
$migration$;

ALTER TABLE event_performer_requests ALTER COLUMN id SET NOT NULL;
ALTER TABLE event_performer_requests ALTER COLUMN event_id SET NOT NULL;
ALTER TABLE event_performer_requests ALTER COLUMN performer_name_snapshot SET NOT NULL;
ALTER TABLE event_performer_requests ALTER COLUMN status SET NOT NULL;
ALTER TABLE event_performer_requests ALTER COLUMN requested_by_user_id SET NOT NULL;
ALTER TABLE event_performer_requests ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE event_performer_requests ALTER COLUMN version SET NOT NULL;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'event_performer_requests'::regclass AND contype = 'p'
    ) THEN
        ALTER TABLE event_performer_requests
            ADD CONSTRAINT pk_event_performer_requests PRIMARY KEY (id);
    END IF;
END
$migration$;

ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS uk_event_performer_request_event;
ALTER TABLE event_performer_requests
    ADD CONSTRAINT uk_event_performer_request_event UNIQUE (event_id);

ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS ck_event_performer_request_target;
ALTER TABLE event_performer_requests
    ADD CONSTRAINT ck_event_performer_request_target
    CHECK ((musician_profile_id IS NOT NULL AND band_id IS NULL)
        OR (musician_profile_id IS NULL AND band_id IS NOT NULL)) NOT VALID;
ALTER TABLE event_performer_requests VALIDATE CONSTRAINT ck_event_performer_request_target;

ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS ck_event_performer_request_status;
ALTER TABLE event_performer_requests
    ADD CONSTRAINT ck_event_performer_request_status
    CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED')) NOT VALID;
ALTER TABLE event_performer_requests VALIDATE CONSTRAINT ck_event_performer_request_status;

ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS ck_event_performer_request_snapshot;
ALTER TABLE event_performer_requests
    ADD CONSTRAINT ck_event_performer_request_snapshot
    CHECK (btrim(performer_name_snapshot) <> '') NOT VALID;
ALTER TABLE event_performer_requests VALIDATE CONSTRAINT ck_event_performer_request_snapshot;

ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS ck_event_performer_request_decision;
ALTER TABLE event_performer_requests
    ADD CONSTRAINT ck_event_performer_request_decision CHECK (
        (status = 'PENDING' AND decided_by_user_id IS NULL AND decided_at IS NULL)
        OR
        (status IN ('ACCEPTED', 'REJECTED') AND decided_by_user_id IS NOT NULL AND decided_at IS NOT NULL)
    ) NOT VALID;
ALTER TABLE event_performer_requests VALIDATE CONSTRAINT ck_event_performer_request_decision;

ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS ck_event_performer_request_version;
ALTER TABLE event_performer_requests
    ADD CONSTRAINT ck_event_performer_request_version CHECK (version >= 0) NOT VALID;
ALTER TABLE event_performer_requests VALIDATE CONSTRAINT ck_event_performer_request_version;

ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS fk_event_performer_request_event;
ALTER TABLE event_performer_requests
    ADD CONSTRAINT fk_event_performer_request_event
    FOREIGN KEY (event_id) REFERENCES tbl_event(id) ON DELETE CASCADE;

ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS fk_event_performer_request_musician;
ALTER TABLE event_performer_requests
    ADD CONSTRAINT fk_event_performer_request_musician
    FOREIGN KEY (musician_profile_id) REFERENCES tbl_musician_profile(id) ON DELETE RESTRICT;

ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS fk_event_performer_request_band;
ALTER TABLE event_performer_requests
    ADD CONSTRAINT fk_event_performer_request_band
    FOREIGN KEY (band_id) REFERENCES tbl_band(id) ON DELETE RESTRICT;

ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS fk_event_performer_request_requested_by;
ALTER TABLE event_performer_requests
    ADD CONSTRAINT fk_event_performer_request_requested_by
    FOREIGN KEY (requested_by_user_id) REFERENCES tbl_user(id);

ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS fk_event_performer_request_decided_by;
ALTER TABLE event_performer_requests
    ADD CONSTRAINT fk_event_performer_request_decided_by
    FOREIGN KEY (decided_by_user_id) REFERENCES tbl_user(id);

CREATE INDEX IF NOT EXISTS idx_event_performer_request_musician_status
    ON event_performer_requests (musician_profile_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_event_performer_request_band_status
    ON event_performer_requests (band_id, status, created_at DESC);

-- Notification delivery is part of the consent contract. Persist each message
-- in the same database transaction as the event/request so a RabbitMQ outage
-- cannot silently lose the only actionable approval notification.
CREATE TABLE IF NOT EXISTS tbl_event_performer_notification_outbox (
    event_id uuid,
    recipient_id uuid,
    notification_type varchar(64),
    title varchar(160),
    message varchar(1000),
    payload jsonb,
    email_force boolean DEFAULT false,
    occurred_at timestamp with time zone,
    status varchar(24),
    attempt_count integer DEFAULT 0,
    next_attempt_at timestamp with time zone,
    lease_owner varchar(100),
    lease_until timestamp with time zone,
    last_error_type varchar(200),
    published_at timestamp with time zone,
    created_at timestamp with time zone,
    updated_at timestamp with time zone
);

-- Reconcile a local table that Hibernate may have created before the SQL was
-- applied. Production still receives the same canonical constraints below.
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS event_id uuid;
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS recipient_id uuid;
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS notification_type varchar(64);
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS title varchar(160);
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS message varchar(1000);
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS payload jsonb;
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS email_force boolean;
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS occurred_at timestamp with time zone;
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS status varchar(24);
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS attempt_count integer;
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS next_attempt_at timestamp with time zone;
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS lease_owner varchar(100);
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS lease_until timestamp with time zone;
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS last_error_type varchar(200);
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS published_at timestamp with time zone;
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS created_at timestamp with time zone;
ALTER TABLE tbl_event_performer_notification_outbox ADD COLUMN IF NOT EXISTS updated_at timestamp with time zone;

UPDATE tbl_event_performer_notification_outbox SET email_force = false WHERE email_force IS NULL;
UPDATE tbl_event_performer_notification_outbox SET attempt_count = 0 WHERE attempt_count IS NULL;

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM tbl_event_performer_notification_outbox outbox
        WHERE outbox.event_id IS NULL
           OR outbox.recipient_id IS NULL
           OR outbox.notification_type IS NULL
           OR btrim(outbox.notification_type) = ''
           OR outbox.title IS NULL
           OR char_length(btrim(outbox.title)) NOT BETWEEN 1 AND 160
           OR outbox.message IS NULL
           OR char_length(btrim(outbox.message)) NOT BETWEEN 1 AND 1000
           OR outbox.payload IS NULL
           OR jsonb_typeof(outbox.payload) <> 'object'
           OR outbox.occurred_at IS NULL
           OR outbox.status NOT IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER')
           OR outbox.attempt_count < 0
           OR outbox.next_attempt_at IS NULL
           OR outbox.created_at IS NULL
           OR outbox.updated_at IS NULL
           OR ((outbox.status = 'IN_FLIGHT') <> (outbox.lease_owner IS NOT NULL AND outbox.lease_until IS NOT NULL))
           OR ((outbox.status = 'PUBLISHED') <> (outbox.published_at IS NOT NULL))
           OR (outbox.last_error_type IS NOT NULL
               AND char_length(btrim(outbox.last_error_type)) NOT BETWEEN 1 AND 200)
    ) THEN
        RAISE EXCEPTION 'Existing event performer notification outbox data violates the canonical contract';
    END IF;
END
$migration$;

ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN event_id SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN recipient_id SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN notification_type SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN title SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN message SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN payload SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN email_force SET DEFAULT false;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN email_force SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN occurred_at SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN status SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN attempt_count SET DEFAULT 0;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN attempt_count SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN next_attempt_at SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE tbl_event_performer_notification_outbox ALTER COLUMN updated_at SET NOT NULL;

DO $migration$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'tbl_event_performer_notification_outbox'::regclass AND contype = 'p'
    ) THEN
        ALTER TABLE tbl_event_performer_notification_outbox
            ADD CONSTRAINT pk_event_performer_notification_outbox PRIMARY KEY (event_id);
    END IF;
END
$migration$;

ALTER TABLE tbl_event_performer_notification_outbox DROP CONSTRAINT IF EXISTS ck_event_performer_notification_outbox_status;
ALTER TABLE tbl_event_performer_notification_outbox
    ADD CONSTRAINT ck_event_performer_notification_outbox_status
    CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER')) NOT VALID;
ALTER TABLE tbl_event_performer_notification_outbox VALIDATE CONSTRAINT ck_event_performer_notification_outbox_status;

ALTER TABLE tbl_event_performer_notification_outbox DROP CONSTRAINT IF EXISTS ck_event_performer_notification_outbox_attempt_count;
ALTER TABLE tbl_event_performer_notification_outbox
    ADD CONSTRAINT ck_event_performer_notification_outbox_attempt_count CHECK (attempt_count >= 0) NOT VALID;
ALTER TABLE tbl_event_performer_notification_outbox VALIDATE CONSTRAINT ck_event_performer_notification_outbox_attempt_count;

ALTER TABLE tbl_event_performer_notification_outbox DROP CONSTRAINT IF EXISTS ck_event_performer_notification_outbox_payload;
ALTER TABLE tbl_event_performer_notification_outbox
    ADD CONSTRAINT ck_event_performer_notification_outbox_payload CHECK (jsonb_typeof(payload) = 'object') NOT VALID;
ALTER TABLE tbl_event_performer_notification_outbox VALIDATE CONSTRAINT ck_event_performer_notification_outbox_payload;

ALTER TABLE tbl_event_performer_notification_outbox DROP CONSTRAINT IF EXISTS ck_event_performer_notification_outbox_title;
ALTER TABLE tbl_event_performer_notification_outbox
    ADD CONSTRAINT ck_event_performer_notification_outbox_title
    CHECK (char_length(btrim(title)) BETWEEN 1 AND 160) NOT VALID;
ALTER TABLE tbl_event_performer_notification_outbox VALIDATE CONSTRAINT ck_event_performer_notification_outbox_title;

ALTER TABLE tbl_event_performer_notification_outbox DROP CONSTRAINT IF EXISTS ck_event_performer_notification_outbox_message;
ALTER TABLE tbl_event_performer_notification_outbox
    ADD CONSTRAINT ck_event_performer_notification_outbox_message
    CHECK (char_length(btrim(message)) BETWEEN 1 AND 1000) NOT VALID;
ALTER TABLE tbl_event_performer_notification_outbox VALIDATE CONSTRAINT ck_event_performer_notification_outbox_message;

ALTER TABLE tbl_event_performer_notification_outbox DROP CONSTRAINT IF EXISTS ck_event_performer_notification_outbox_lease;
ALTER TABLE tbl_event_performer_notification_outbox
    ADD CONSTRAINT ck_event_performer_notification_outbox_lease CHECK (
        (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL)
    ) NOT VALID;
ALTER TABLE tbl_event_performer_notification_outbox VALIDATE CONSTRAINT ck_event_performer_notification_outbox_lease;

ALTER TABLE tbl_event_performer_notification_outbox DROP CONSTRAINT IF EXISTS ck_event_performer_notification_outbox_published;
ALTER TABLE tbl_event_performer_notification_outbox
    ADD CONSTRAINT ck_event_performer_notification_outbox_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    ) NOT VALID;
ALTER TABLE tbl_event_performer_notification_outbox VALIDATE CONSTRAINT ck_event_performer_notification_outbox_published;

ALTER TABLE tbl_event_performer_notification_outbox DROP CONSTRAINT IF EXISTS ck_event_performer_notification_outbox_error_type;
ALTER TABLE tbl_event_performer_notification_outbox
    ADD CONSTRAINT ck_event_performer_notification_outbox_error_type CHECK (
        last_error_type IS NULL OR char_length(btrim(last_error_type)) BETWEEN 1 AND 200
    ) NOT VALID;
ALTER TABLE tbl_event_performer_notification_outbox VALIDATE CONSTRAINT ck_event_performer_notification_outbox_error_type;

CREATE INDEX IF NOT EXISTS idx_event_performer_notification_outbox_due
    ON tbl_event_performer_notification_outbox (status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_event_performer_notification_outbox_lease
    ON tbl_event_performer_notification_outbox (status, lease_until);
CREATE INDEX IF NOT EXISTS idx_event_performer_notification_outbox_created
    ON tbl_event_performer_notification_outbox (created_at);
CREATE INDEX IF NOT EXISTS idx_event_performer_notification_outbox_published
    ON tbl_event_performer_notification_outbox (status, published_at);

-- Legacy links converted to PENDING above need the same durable notification
-- contract as requests created by the application. Keep this after the outbox
-- reconciliation/constraints so it is safe for both clean and Hibernate-created
-- schemas. The temporary legacy table makes a successful rerun a no-op, while
-- the semantic NOT EXISTS fence also protects a partially pre-seeded outbox.
WITH migrated_requests AS (
    SELECT
        request.id AS request_id,
        request.event_id,
        request.musician_profile_id,
        request.band_id,
        request.performer_name_snapshot,
        event.venue_id,
        coalesce(nullif(btrim(venue.name), ''), 'Mekân') AS venue_name,
        coalesce(nullif(btrim(event.title), ''), 'Etkinlik') AS event_title
    FROM event_performer_requests request
    JOIN event_performer_legacy_unconnected legacy ON legacy.event_id = request.event_id
    JOIN tbl_event event ON event.id = request.event_id
    JOIN tbl_venues venue ON venue.id = event.venue_id
    WHERE request.status = 'PENDING'
), recipient_rows AS (
    SELECT migrated.*, musician.user_id AS recipient_id
    FROM migrated_requests migrated
    JOIN tbl_musician_profile musician ON musician.id = migrated.musician_profile_id
    WHERE migrated.musician_profile_id IS NOT NULL

    UNION ALL

    SELECT migrated.*, member.user_id AS recipient_id
    FROM migrated_requests migrated
    JOIN tbl_band_member member
      ON member.band_id = migrated.band_id
     AND member.status = 'ACTIVE'
     AND member.band_role = 'FOUNDER'
    WHERE migrated.band_id IS NOT NULL
), distinct_recipients AS (
    SELECT DISTINCT
        request_id,
        event_id,
        musician_profile_id,
        band_id,
        performer_name_snapshot,
        venue_id,
        venue_name,
        event_title,
        recipient_id
    FROM recipient_rows
    WHERE recipient_id IS NOT NULL
), seeded AS (
    SELECT
        recipient.*,
        decode(md5(
            'EVENT_PERFORMER|APPROVAL_REQUESTED|'
            || recipient.event_id::text || '|'
            || recipient.request_id::text || '|'
            || recipient.recipient_id::text
        ), 'hex') AS name_digest
    FROM distinct_recipients recipient
)
INSERT INTO tbl_event_performer_notification_outbox(
    event_id,
    recipient_id,
    notification_type,
    title,
    message,
    payload,
    email_force,
    occurred_at,
    status,
    attempt_count,
    next_attempt_at,
    lease_owner,
    lease_until,
    last_error_type,
    published_at,
    created_at,
    updated_at
)
SELECT
    encode(
        set_byte(
            set_byte(
                seeded.name_digest,
                6,
                (get_byte(seeded.name_digest, 6) & 15) | 48
            ),
            8,
            (get_byte(seeded.name_digest, 8) & 63) | 128
        ),
        'hex'
    )::uuid,
    seeded.recipient_id,
    'EVENT_PERFORMER_APPROVAL_REQUESTED',
    CASE WHEN seeded.band_id IS NULL
        THEN 'Etkinlik katılım onayı'
        ELSE 'Grubunuz için etkinlik katılım onayı'
    END,
    seeded.venue_name || ', “' || seeded.event_title || '” etkinliğine '
        || CASE WHEN seeded.band_id IS NULL
            THEN 'seni'
            ELSE '“' || seeded.performer_name_snapshot || '” adlı grubunuzu'
        END || ' eklemek istiyor.',
    jsonb_build_object(
        'module', 'EVENT_PERFORMER',
        'action', 'APPROVAL_REQUESTED',
        'eventId', seeded.event_id::text,
        'venueId', seeded.venue_id::text,
        'venueName', seeded.venue_name,
        'eventTitle', seeded.event_title,
        'performerName', seeded.performer_name_snapshot,
        'performerType', CASE WHEN seeded.band_id IS NULL THEN 'MUSICIAN' ELSE 'BAND' END,
        'requestId', seeded.request_id::text,
        'status', 'PENDING',
        'availableActions', jsonb_build_array('ACCEPT', 'REJECT')
    ) || CASE
        WHEN seeded.musician_profile_id IS NOT NULL
            THEN jsonb_build_object('musicianProfileId', seeded.musician_profile_id::text)
        ELSE jsonb_build_object('bandId', seeded.band_id::text)
    END,
    false,
    CURRENT_TIMESTAMP,
    'PENDING',
    0,
    CURRENT_TIMESTAMP,
    NULL,
    NULL,
    NULL,
    NULL,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM seeded
WHERE NOT EXISTS (
    SELECT 1
    FROM tbl_event_performer_notification_outbox existing
    WHERE existing.recipient_id = seeded.recipient_id
      AND existing.notification_type = 'EVENT_PERFORMER_APPROVAL_REQUESTED'
      AND existing.payload ->> 'requestId' = seeded.request_id::text
)
ON CONFLICT (event_id) DO NOTHING;

COMMIT;
