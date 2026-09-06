-- Separate venue-side performer linkage from explicit profile-calendar consent.
-- Run after 2026-09-04-event-performer-consent.sql with API/event writers stopped.
-- Additive, transactional and rerunnable: existing events and decisions survive.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

DO $migration$
BEGIN
    IF to_regclass('tbl_event') IS NULL
       OR to_regclass('event_performer_requests') IS NULL
       OR to_regclass('tbl_event_performer_notification_outbox') IS NULL THEN
        RAISE EXCEPTION 'Apply the event performer consent migration first';
    END IF;
END
$migration$;

ALTER TABLE tbl_event ADD COLUMN IF NOT EXISTS profile_calendar_approved boolean;
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS request_purpose varchar(30);
UPDATE event_performer_requests
SET request_purpose = 'PERFORMER_CONSENT'
WHERE request_purpose IS NULL;
ALTER TABLE event_performer_requests ALTER COLUMN request_purpose SET DEFAULT 'PERFORMER_CONSENT';
ALTER TABLE event_performer_requests ALTER COLUMN request_purpose SET NOT NULL;
ALTER TABLE event_performer_requests DROP CONSTRAINT IF EXISTS ck_event_performer_request_purpose;
ALTER TABLE event_performer_requests ADD CONSTRAINT ck_event_performer_request_purpose
    CHECK (request_purpose IN ('PERFORMER_CONSENT', 'PROFILE_VISIBILITY'));

-- On the first transition, legacy acceptance is authoritative; a venue connection is not.
-- Exact target matching prevents a stale/corrupt request from authorizing another profile.
DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM event_performer_requests request
        JOIN tbl_event event ON event.id = request.event_id
        WHERE (request.status = 'ACCEPTED' OR request.request_purpose = 'PROFILE_VISIBILITY')
          AND (event.performer_approval_status <> 'APPROVED'
               OR event.manual_performer_name IS NOT NULL
               OR NOT (event.musician_profile_id IS NOT DISTINCT FROM request.musician_profile_id
                       AND event.band_id IS NOT DISTINCT FROM request.band_id))
    ) THEN
        RAISE EXCEPTION 'An accepted/profile-visibility request does not match its event public performer link';
    END IF;
END
$migration$;

-- Backfill only an uninitialized value. After the per-event opt-in split, an
-- ACCEPTED participation request may deliberately keep this flag false.
-- Replaying the migration must never turn that explicit choice into consent,
-- nor revoke an already-recorded true choice. Existing non-null values win.
UPDATE tbl_event event
SET profile_calendar_approved = EXISTS (
    SELECT 1 FROM event_performer_requests request
    WHERE request.event_id = event.id AND request.status = 'ACCEPTED'
      AND event.performer_approval_status = 'APPROVED'
      AND request.musician_profile_id IS NOT DISTINCT FROM event.musician_profile_id
      AND request.band_id IS NOT DISTINCT FROM event.band_id
)
WHERE coalesce(to_jsonb(event) ->> 'event_origin', 'VENUE') = 'VENUE'
AND event.profile_calendar_approved IS NULL;
ALTER TABLE tbl_event ALTER COLUMN profile_calendar_approved SET DEFAULT false;
ALTER TABLE tbl_event ALTER COLUMN profile_calendar_approved SET NOT NULL;
ALTER TABLE tbl_event DROP CONSTRAINT IF EXISTS ck_event_profile_calendar_consent;
ALTER TABLE tbl_event ADD CONSTRAINT ck_event_profile_calendar_consent
    CHECK (NOT profile_calendar_approved OR performer_approval_status = 'APPROVED');

-- Previously auto-linked events stay linked on the venue profile, but now need
-- an actionable request before any performer calendar may include them.
-- Ask only for current/future events to avoid sending a historical notification
-- flood. Older unconsented events remain hidden; existing explicit acceptances
-- above are preserved regardless of their event date.
CREATE TEMPORARY TABLE event_profile_visibility_legacy ON COMMIT DROP AS
SELECT event.id AS event_id, event.musician_profile_id, event.band_id,
       venue.owner_id AS requested_by_user_id,
       CASE WHEN event.band_id IS NOT NULL THEN nullif(btrim(band.name), '')
            ELSE coalesce(nullif(btrim(owner_user.user_name), ''), nullif(btrim(musician.stage_name), ''))
       END AS performer_name_snapshot
FROM tbl_event event
JOIN tbl_venues venue ON venue.id = event.venue_id
LEFT JOIN tbl_musician_profile musician ON musician.id = event.musician_profile_id
LEFT JOIN tbl_user owner_user ON owner_user.id = musician.user_id
LEFT JOIN tbl_band band ON band.id = event.band_id
WHERE event.performer_approval_status = 'APPROVED'
  AND coalesce(to_jsonb(event) ->> 'event_origin', 'VENUE') = 'VENUE'
  AND event.event_date >= CURRENT_DATE
  AND NOT event.profile_calendar_approved
  AND NOT EXISTS (SELECT 1 FROM event_performer_requests request WHERE request.event_id = event.id);

DO $migration$
BEGIN
    IF EXISTS (
        SELECT 1 FROM event_profile_visibility_legacy legacy
        WHERE legacy.performer_name_snapshot IS NULL
           OR char_length(legacy.performer_name_snapshot) > 100
           OR legacy.requested_by_user_id IS NULL
           OR (legacy.band_id IS NOT NULL AND NOT EXISTS (
               SELECT 1 FROM tbl_band_member member
               WHERE member.band_id = legacy.band_id AND member.status = 'ACTIVE' AND member.band_role = 'FOUNDER'
           ))
           OR (legacy.musician_profile_id IS NOT NULL AND NOT EXISTS (
               SELECT 1 FROM tbl_musician_profile musician
               WHERE musician.id = legacy.musician_profile_id AND musician.user_id IS NOT NULL
           ))
    ) THEN
        RAISE EXCEPTION 'A legacy linked event has no valid performer name, venue owner, or authorized profile decider';
    END IF;
END
$migration$;

INSERT INTO event_performer_requests (
    id, created_at, updated_at, event_id, musician_profile_id, band_id,
    performer_name_snapshot, status, request_purpose, requested_by_user_id,
    decided_by_user_id, decided_at, version
)
SELECT md5('soundconnect:event-profile-visibility:' || legacy.event_id::text)::uuid,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, legacy.event_id,
       legacy.musician_profile_id, legacy.band_id, legacy.performer_name_snapshot,
       'PENDING', 'PROFILE_VISIBILITY', legacy.requested_by_user_id, NULL, NULL, 0
FROM event_profile_visibility_legacy legacy
ON CONFLICT (event_id) DO NOTHING;

WITH migrated_requests AS (
    SELECT request.id AS request_id, request.event_id, request.musician_profile_id,
           request.band_id, request.performer_name_snapshot, event.venue_id,
           coalesce(nullif(btrim(venue.name), ''), 'Mekân') AS venue_name,
           coalesce(nullif(btrim(event.title), ''), 'Etkinlik') AS event_title
    FROM event_performer_requests request
    JOIN event_profile_visibility_legacy legacy ON legacy.event_id = request.event_id
    JOIN tbl_event event ON event.id = request.event_id
    JOIN tbl_venues venue ON venue.id = event.venue_id
    WHERE request.status = 'PENDING' AND request.request_purpose = 'PROFILE_VISIBILITY'
), recipients AS (
    SELECT request.*, musician.user_id AS recipient_id
    FROM migrated_requests request
    JOIN tbl_musician_profile musician ON musician.id = request.musician_profile_id
    UNION
    SELECT request.*, member.user_id AS recipient_id
    FROM migrated_requests request
    JOIN tbl_band_member member ON member.band_id = request.band_id
    WHERE member.status = 'ACTIVE' AND member.band_role = 'FOUNDER'
), seeded AS (
    SELECT recipient.*, decode(md5('EVENT_PERFORMER|APPROVAL_REQUESTED|'
           || recipient.event_id::text || '|' || recipient.request_id::text || '|'
           || recipient.recipient_id::text), 'hex') AS name_digest
    FROM recipients recipient WHERE recipient.recipient_id IS NOT NULL
)
INSERT INTO tbl_event_performer_notification_outbox (
    event_id, recipient_id, notification_type, title, message, payload,
    email_force, occurred_at, status, attempt_count, next_attempt_at,
    lease_owner, lease_until, last_error_type, published_at, created_at, updated_at
)
SELECT encode(set_byte(set_byte(seeded.name_digest, 6,
           (get_byte(seeded.name_digest, 6) & 15) | 48), 8,
           (get_byte(seeded.name_digest, 8) & 63) | 128), 'hex')::uuid,
       seeded.recipient_id, 'EVENT_PERFORMER_APPROVAL_REQUESTED',
       CASE WHEN seeded.band_id IS NULL THEN 'Profilinde gösterilsin mi?'
            ELSE 'Grubunuzun profilinde gösterilsin mi?' END,
       seeded.venue_name || ', “' || seeded.event_title || '” etkinliğine '
           || CASE WHEN seeded.band_id IS NULL THEN 'seni'
                   ELSE '“' || seeded.performer_name_snapshot || '” adlı grubunuzu' END
           || ' ekledi. Etkinliğin '
           || CASE WHEN seeded.band_id IS NULL THEN 'profilindeki' ELSE 'grubunuzun profilindeki' END
           || ' takvimde de gösterilmesini onaylıyor musun?',
       jsonb_build_object(
           'module', 'EVENT_PERFORMER', 'action', 'APPROVAL_REQUESTED',
           'eventId', seeded.event_id::text, 'venueId', seeded.venue_id::text,
           'venueName', seeded.venue_name, 'eventTitle', seeded.event_title,
           'performerName', seeded.performer_name_snapshot,
           'performerType', CASE WHEN seeded.band_id IS NULL THEN 'MUSICIAN' ELSE 'BAND' END,
           'requestId', seeded.request_id::text, 'status', 'PENDING',
           'requestPurpose', 'PROFILE_VISIBILITY', 'availableActions', jsonb_build_array('ACCEPT', 'REJECT')
       ) || CASE WHEN seeded.musician_profile_id IS NOT NULL
           THEN jsonb_build_object('musicianProfileId', seeded.musician_profile_id::text)
           ELSE jsonb_build_object('bandId', seeded.band_id::text) END,
       false, CURRENT_TIMESTAMP, 'PENDING', 0, CURRENT_TIMESTAMP,
       NULL, NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM seeded
WHERE NOT EXISTS (
    SELECT 1 FROM tbl_event_performer_notification_outbox existing
    WHERE existing.recipient_id = seeded.recipient_id
      AND existing.notification_type = 'EVENT_PERFORMER_APPROVAL_REQUESTED'
      AND existing.payload ->> 'requestId' = seeded.request_id::text
)
ON CONFLICT (event_id) DO NOTHING;

COMMIT;
