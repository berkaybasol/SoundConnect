-- Deploy with event/profile writes stopped. Run after the 2026-09-05 event and
-- performer-calendar migrations, BEFORE starting the new application version.
-- Retains events, participation links and original invitation decisions.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
CREATE TABLE IF NOT EXISTS soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
LOCK TABLE tbl_event, event_performer_requests, tbl_musician_calendar_settings,
    tbl_band_calendar_settings, tbl_band_member IN SHARE ROW EXCLUSIVE MODE;
ALTER TABLE tbl_event ADD COLUMN IF NOT EXISTS profile_publication_version bigint NOT NULL DEFAULT 0;
ALTER TABLE tbl_event ALTER COLUMN profile_publication_version SET DEFAULT 0;
ALTER TABLE tbl_event DROP CONSTRAINT IF EXISTS ck_event_profile_publication_version;
ALTER TABLE tbl_event ADD CONSTRAINT ck_event_profile_publication_version CHECK (profile_publication_version >= 0);
ALTER TABLE event_performer_requests ADD COLUMN IF NOT EXISTS accepted_profile_publication boolean;
CREATE TABLE IF NOT EXISTS event_member_publications (
    event_id uuid NOT NULL REFERENCES tbl_event(id) ON DELETE CASCADE,
    musician_profile_id uuid NOT NULL REFERENCES tbl_musician_profile(id) ON DELETE CASCADE,
    visible boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (event_id, musician_profile_id)
);
CREATE INDEX IF NOT EXISTS idx_member_publication_profile_visible
    ON event_member_publications(musician_profile_id, event_id) WHERE visible;
-- Also normalize tables created by Hibernate before this deployment script.
ALTER TABLE event_member_publications ALTER COLUMN visible SET DEFAULT false,
    ALTER COLUMN version SET DEFAULT 0;
DO $$
DECLARE existing_fk record;
BEGIN
    FOR existing_fk IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'event_member_publications'::regclass AND contype = 'f'
    LOOP
        EXECUTE format('ALTER TABLE event_member_publications DROP CONSTRAINT %I', existing_fk.conname);
    END LOOP;
    ALTER TABLE event_member_publications ADD CONSTRAINT fk_member_publication_event
        FOREIGN KEY (event_id) REFERENCES tbl_event(id) ON DELETE CASCADE;
    ALTER TABLE event_member_publications ADD CONSTRAINT fk_member_publication_profile
        FOREIGN KEY (musician_profile_id) REFERENCES tbl_musician_profile(id) ON DELETE CASCADE;
END $$;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM soundconnect_schema_migrations WHERE migration_id = '2026-09-06-event-profile-publications') THEN
        RETURN;
    END IF;
    -- Snapshot the original decision BEFORE freezing formerly hidden publications.
    UPDATE event_performer_requests request SET accepted_profile_publication = event.profile_calendar_approved
    FROM tbl_event event WHERE event.id = request.event_id AND request.status = 'ACCEPTED'
        AND request.accepted_profile_publication IS NULL;
    -- Only old effectively visible band/member combinations receive a publication.
    -- New members and previously hidden profiles never inherit a group decision.
    INSERT INTO event_member_publications(event_id, musician_profile_id, visible, version)
    SELECT DISTINCT event.id, profile.id, true, 1
    FROM tbl_event event
    JOIN tbl_venues venue ON venue.id = event.venue_id AND venue.status = 'APPROVED'
    JOIN tbl_band_calendar_settings band_setting ON band_setting.band_id = event.band_id AND band_setting.visible
    JOIN tbl_band_member member ON member.band_id = event.band_id AND member.status = 'ACTIVE'
    JOIN tbl_musician_profile profile ON profile.user_id = member.user_id
    JOIN tbl_musician_calendar_settings setting ON setting.musician_profile_id = profile.id AND setting.visible
    WHERE event.event_origin = 'VENUE' AND event.performer_approval_status = 'APPROVED' AND event.profile_calendar_approved
    ON CONFLICT (event_id, musician_profile_id) DO NOTHING;
    UPDATE tbl_event event SET profile_calendar_approved = false,
        profile_publication_version = profile_publication_version + 1
    WHERE event.event_origin = 'VENUE' AND event.profile_calendar_approved AND (
        (event.musician_profile_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM tbl_musician_calendar_settings setting
            WHERE setting.musician_profile_id = event.musician_profile_id AND setting.visible))
        OR (event.band_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM tbl_band_calendar_settings setting WHERE setting.band_id = event.band_id AND setting.visible))
    );
    INSERT INTO soundconnect_schema_migrations(migration_id) VALUES ('2026-09-06-event-profile-publications');
END $$;
COMMIT;
