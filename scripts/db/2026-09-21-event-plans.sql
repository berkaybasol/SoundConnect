-- Additive. Apply explicitly with application writers stopped; never automatically on startup.
BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';
CREATE TABLE IF NOT EXISTS event_plans (
    id uuid PRIMARY KEY,
    organizer_user_id uuid NOT NULL CONSTRAINT fk_event_plan_owner REFERENCES tbl_user(id),
    venue_id uuid NOT NULL CONSTRAINT fk_event_plan_venue REFERENCES tbl_venues(id),
    client_request_id uuid NOT NULL,
    creation_hash varchar(64) NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK(version >= 0),
    consent_revision bigint NOT NULL DEFAULT 0 CHECK(consent_revision >= 0),
    start_date date NOT NULL,
    until_date date,
    weekday_mask integer NOT NULL CHECK(weekday_mask BETWEEN 1 AND 127),
    excluded_dates jsonb NOT NULL DEFAULT '[]'::jsonb CHECK(jsonb_typeof(excluded_dates) = 'array'),
    title varchar(255) NOT NULL,
    description varchar(500),
    start_time time NOT NULL,
    end_time time,
    poster_image varchar(255),
    -- Deliberate immutable target identities, without FKs: deletion cannot silently retarget a series.
    musician_profile_id uuid,
    band_id uuid,
    manual_performer_name varchar(120),
    performer_name_snapshot varchar(120),
    status varchar(20) NOT NULL CHECK(status IN ('ACTIVE','STOPPED','COMPLETED')),
    consent_status varchar(20) NOT NULL CHECK(consent_status IN ('NOT_REQUIRED','PENDING','ACCEPTED','REJECTED','WITHDRAWN')),
    show_on_profile boolean NOT NULL DEFAULT false,
    accepted_publication boolean,
    decided_by_user_id uuid,
    generated_through date,
    cancel_future boolean NOT NULL DEFAULT false,
    created_at timestamp,
    updated_at timestamp,
    CONSTRAINT uk_event_plan_creation UNIQUE(organizer_user_id,client_request_id),
    CONSTRAINT ck_event_plan_range CHECK(until_date IS NULL OR until_date >= start_date),
    CONSTRAINT ck_event_plan_target CHECK(num_nonnulls(musician_profile_id,band_id,manual_performer_name) <= 1),
    CONSTRAINT ck_event_plan_publication CHECK(NOT show_on_profile OR consent_status = 'ACCEPTED')
);
CREATE INDEX IF NOT EXISTS idx_event_plan_due ON event_plans(status,generated_through,start_date,id);
CREATE INDEX IF NOT EXISTS idx_event_plan_venue ON event_plans(venue_id,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_event_plan_musician ON event_plans(musician_profile_id,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_event_plan_band ON event_plans(band_id,created_at DESC,id DESC);
CREATE TABLE IF NOT EXISTS event_plan_occurrences (
    plan_id uuid NOT NULL CONSTRAINT fk_event_plan_occurrence_plan REFERENCES event_plans(id),
    scheduled_date date NOT NULL,
    event_id uuid REFERENCES tbl_event(id) ON DELETE SET NULL,
    event_date date NOT NULL,
    override_musician_profile_id uuid,
    override_band_id uuid,
    override_manual_performer_name varchar(120),
    status varchar(20) NOT NULL CHECK(status IN ('GENERATED','OVERRIDDEN','SKIPPED','CANCELLED')),
    PRIMARY KEY(plan_id,scheduled_date),
    CONSTRAINT uk_event_plan_occurrence_event UNIQUE(event_id)
);
CREATE INDEX IF NOT EXISTS idx_event_plan_occurrence_future ON event_plan_occurrences(plan_id,event_date,scheduled_date);
ALTER TABLE event_plan_occurrences ADD COLUMN IF NOT EXISTS override_musician_profile_id uuid,
    ADD COLUMN IF NOT EXISTS override_band_id uuid, ADD COLUMN IF NOT EXISTS override_manual_performer_name varchar(120);
-- Install named constraints even when Hibernate created the tables before this deployment.
ALTER TABLE event_plans DROP CONSTRAINT IF EXISTS ck_event_plan_version,
    DROP CONSTRAINT IF EXISTS ck_event_plan_revision, DROP CONSTRAINT IF EXISTS ck_event_plan_weekdays,
    DROP CONSTRAINT IF EXISTS ck_event_plan_exclusions, DROP CONSTRAINT IF EXISTS ck_event_plan_status,
    DROP CONSTRAINT IF EXISTS ck_event_plan_consent, DROP CONSTRAINT IF EXISTS ck_event_plan_range,
    DROP CONSTRAINT IF EXISTS ck_event_plan_target, DROP CONSTRAINT IF EXISTS ck_event_plan_publication;
ALTER TABLE event_plans ADD CONSTRAINT ck_event_plan_version CHECK(version>=0),
    ADD CONSTRAINT ck_event_plan_revision CHECK(consent_revision>=0),
    ADD CONSTRAINT ck_event_plan_weekdays CHECK(weekday_mask BETWEEN 1 AND 127),
    ADD CONSTRAINT ck_event_plan_exclusions CHECK(jsonb_typeof(excluded_dates)='array'),
    ADD CONSTRAINT ck_event_plan_status CHECK(status IN ('ACTIVE','STOPPED','COMPLETED')),
    ADD CONSTRAINT ck_event_plan_consent CHECK(consent_status IN ('NOT_REQUIRED','PENDING','ACCEPTED','REJECTED','WITHDRAWN')),
    ADD CONSTRAINT ck_event_plan_range CHECK(until_date IS NULL OR until_date>=start_date),
    ADD CONSTRAINT ck_event_plan_target CHECK(num_nonnulls(musician_profile_id,band_id,manual_performer_name)<=1),
    ADD CONSTRAINT ck_event_plan_publication CHECK(NOT show_on_profile OR consent_status='ACCEPTED');
ALTER TABLE event_plan_occurrences DROP CONSTRAINT IF EXISTS ck_event_plan_occurrence_status;
ALTER TABLE event_plan_occurrences ADD CONSTRAINT ck_event_plan_occurrence_status
    CHECK(status IN ('GENERATED','OVERRIDDEN','SKIPPED','CANCELLED'));
-- Hibernate-created local tables must get the same deletion behavior as migrated databases.
DO $$
DECLARE fk record;
BEGIN
    FOR fk IN SELECT c.conname FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid=c.conrelid AND c.conkey=ARRAY[a.attnum]::smallint[]
        WHERE c.conrelid='event_plan_occurrences'::regclass AND c.contype='f' AND a.attname='event_id'
    LOOP EXECUTE format('ALTER TABLE event_plan_occurrences DROP CONSTRAINT %I',fk.conname); END LOOP;
    ALTER TABLE event_plan_occurrences ADD CONSTRAINT fk_event_plan_occurrence_event
        FOREIGN KEY(event_id) REFERENCES tbl_event(id) ON DELETE SET NULL;
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='event_plans'::regclass AND conname='fk_event_plan_owner') THEN
        ALTER TABLE event_plans ADD CONSTRAINT fk_event_plan_owner FOREIGN KEY(organizer_user_id) REFERENCES tbl_user(id);
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='event_plans'::regclass AND conname='fk_event_plan_venue') THEN
        ALTER TABLE event_plans ADD CONSTRAINT fk_event_plan_venue FOREIGN KEY(venue_id) REFERENCES tbl_venues(id);
    END IF;
    IF NOT EXISTS(SELECT 1 FROM pg_constraint WHERE conrelid='event_plan_occurrences'::regclass AND conname='fk_event_plan_occurrence_plan') THEN
        ALTER TABLE event_plan_occurrences ADD CONSTRAINT fk_event_plan_occurrence_plan FOREIGN KEY(plan_id) REFERENCES event_plans(id);
    END IF;
END $$;
COMMIT;
