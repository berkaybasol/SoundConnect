BEGIN;
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS tbl_analytics_report_start (
    singleton boolean PRIMARY KEY CHECK (singleton), started_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS tbl_analytics_account_opening (
    user_id uuid PRIMARY KEY REFERENCES tbl_user ON DELETE CASCADE,
    opened_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS tbl_analytics_baseline_member (
    user_id uuid PRIMARY KEY REFERENCES tbl_user ON DELETE CASCADE
);
-- A closed interval only retains the role and dates needed for historical counts.
CREATE TABLE IF NOT EXISTS tbl_analytics_membership_period (
    user_id uuid, profile_type varchar(12) NOT NULL, state varchar(10) NOT NULL,
    started_at timestamptz NOT NULL, ended_at timestamptz, new_member boolean NOT NULL,
    erased boolean NOT NULL DEFAULT false,
    CHECK ((user_id IS NULL) = (ended_at IS NOT NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_sc_analytics_open_period
    ON tbl_analytics_membership_period(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_sc_analytics_period_dates
    ON tbl_analytics_membership_period(profile_type,started_at,ended_at);

CREATE TABLE IF NOT EXISTS tbl_analytics_week_cohort (
    user_id uuid UNIQUE REFERENCES tbl_user ON DELETE SET NULL,
    opened_at timestamptz NOT NULL, profile_type varchar(12) NOT NULL,
    duration_ms int NOT NULL DEFAULT 0 CHECK (duration_ms BETWEEN 0 AND 480001),
    first_action varchar(48)
);
CREATE INDEX IF NOT EXISTS ix_sc_analytics_week_opened ON tbl_analytics_week_cohort(opened_at,profile_type);
CREATE TABLE IF NOT EXISTS tbl_analytics_week_sample (
    user_id uuid NOT NULL REFERENCES tbl_user ON DELETE CASCADE,
    sample_id uuid NOT NULL, measured tstzrange NOT NULL, expires_at timestamptz NOT NULL, PRIMARY KEY(user_id,sample_id)
);
CREATE INDEX IF NOT EXISTS ix_sc_analytics_sample_expiry ON tbl_analytics_week_sample(expires_at);
CREATE TABLE IF NOT EXISTS tbl_analytics_live_sample (
    sampled_at timestamptz NOT NULL, audience varchar(12) NOT NULL, users_count int NOT NULL CHECK(users_count >= 0),
    PRIMARY KEY(sampled_at,audience)
);
CREATE INDEX IF NOT EXISTS ix_sc_analytics_live_peak ON tbl_analytics_live_sample(audience,sampled_at,users_count);
CREATE TABLE IF NOT EXISTS tbl_analytics_month_peak (
    month_start date NOT NULL, audience varchar(12) NOT NULL, users_count int NOT NULL,
    peak_at timestamptz NOT NULL, samples int NOT NULL, PRIMARY KEY(month_start,audience)
);

CREATE OR REPLACE FUNCTION sc_analytics_enroll_week(who uuid) RETURNS void LANGUAGE sql AS $$
    INSERT INTO tbl_analytics_week_cohort(user_id,opened_at,profile_type)
    SELECT o.user_id,o.opened_at,m.profile_type FROM tbl_analytics_account_opening o
    JOIN tbl_analytics_membership_period m ON m.user_id=o.user_id AND m.state='ACTIVE'
    JOIN tbl_member_analytics_preference p ON p.user_id=o.user_id
    WHERE o.user_id=who AND p.decision='GRANTED' AND p.policy_version='2026-11-26-r2'
      AND p.changed_at < o.opened_at + interval '168 hours' AND now() < o.opened_at + interval '168 hours'
    ON CONFLICT (user_id) DO NOTHING
$$;
CREATE OR REPLACE FUNCTION sc_analytics_week_preference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.decision='GRANTED' THEN PERFORM sc_analytics_enroll_week(NEW.user_id); END IF;
    RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS sc_analytics_week_preference ON tbl_member_analytics_preference;
CREATE TRIGGER sc_analytics_week_preference AFTER INSERT OR UPDATE ON tbl_member_analytics_preference
    FOR EACH ROW EXECUTE FUNCTION sc_analytics_week_preference();

CREATE OR REPLACE FUNCTION sc_analytics_sync_member(who uuid, baseline boolean DEFAULT false)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    account tbl_user%ROWTYPE;
    prior tbl_analytics_membership_period%ROWTYPE;
    kind text;
    stage text;
    is_new boolean := false;
    at_time timestamptz := transaction_timestamp();
BEGIN
    IF who IS NULL THEN RETURN; END IF;
    SELECT * INTO account FROM tbl_user WHERE id=who FOR NO KEY UPDATE;
    SELECT * INTO prior FROM tbl_analytics_membership_period WHERE user_id=who;
    IF account.id IS NOT NULL AND account.erased_at IS NULL THEN
        SELECT CASE WHEN min(e.role)=max(e.role) THEN min(e.role) END INTO kind FROM (
            SELECT substring(r.name FROM 6) AS role FROM user_roles ur JOIN tbl_role r ON r.id=ur.role_id
            WHERE ur.user_id=who AND r.name IN ('ROLE_ADMIN','ROLE_OWNER','ROLE_VENUE','ROLE_STUDIO','ROLE_LISTENER','ROLE_MUSICIAN','ROLE_ORGANIZER','ROLE_PRODUCER')
            UNION ALL SELECT 'LISTENER' FROM "tbl_listener-profile" WHERE user_id=who
            UNION ALL SELECT 'MUSICIAN' FROM tbl_musician_profile WHERE user_id=who
            UNION ALL SELECT 'VENUE' FROM tbl_venues WHERE owner_id=who
            UNION ALL SELECT 'STUDIO' FROM tbl_studio_profile WHERE user_id=who
            UNION ALL SELECT 'ORGANIZER' FROM tbl_organizer_profile WHERE user_id=who
            UNION ALL SELECT 'PRODUCER' FROM tbl_producer_profile WHERE user_id=who
            UNION ALL SELECT 'VENUE' WHERE account.status='PENDING_VENUE_REQUEST'
            UNION ALL SELECT 'STUDIO' WHERE account.status='PENDING_STUDIO_REQUEST'
        ) e;
        IF kind IN ('LISTENER','MUSICIAN','VENUE','STUDIO') THEN
            IF account.status='ACTIVE' AND account.email_verified THEN stage := 'ACTIVE';
            ELSIF account.status IN ('PENDING_VENUE_REQUEST','PENDING_STUDIO_REQUEST') THEN stage := 'PENDING';
            END IF;
        END IF;
    END IF;
    IF prior.user_id IS NOT NULL AND stage IS NOT DISTINCT FROM prior.state AND kind IS NOT DISTINCT FROM prior.profile_type THEN RETURN; END IF;
    IF prior.user_id IS NOT NULL THEN
        UPDATE tbl_analytics_membership_period SET ended_at=greatest(at_time,started_at),user_id=NULL,
            erased=(prior.state='ACTIVE' AND (account.id IS NULL OR account.erased_at IS NOT NULL))
        WHERE user_id=who;
    END IF;
    IF account.id IS NULL OR account.erased_at IS NOT NULL THEN
        DELETE FROM tbl_analytics_week_sample WHERE user_id=who;
        UPDATE tbl_analytics_week_cohort SET user_id=NULL WHERE user_id=who;
        DELETE FROM tbl_analytics_account_opening WHERE user_id=who;
        DELETE FROM tbl_analytics_baseline_member WHERE user_id=who;
    ELSIF stage IS NOT NULL THEN
        IF stage='ACTIVE' AND baseline THEN
            INSERT INTO tbl_analytics_baseline_member VALUES (who) ON CONFLICT DO NOTHING;
        ELSIF stage='ACTIVE' AND NOT EXISTS (SELECT FROM tbl_analytics_baseline_member WHERE user_id=who) THEN
            INSERT INTO tbl_analytics_account_opening VALUES (who,at_time) ON CONFLICT DO NOTHING;
            is_new := FOUND;
        END IF;
        INSERT INTO tbl_analytics_membership_period(user_id,profile_type,state,started_at,new_member)
        VALUES (who,kind,stage,at_time,is_new);
        IF stage='ACTIVE' THEN PERFORM sc_analytics_enroll_week(who); END IF;
    END IF;
END $$;

CREATE OR REPLACE FUNCTION sc_analytics_member_trigger() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_id uuid; new_id uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN old_id := (to_jsonb(OLD)->>TG_ARGV[0])::uuid; END IF;
    IF TG_OP <> 'DELETE' THEN new_id := (to_jsonb(NEW)->>TG_ARGV[0])::uuid; END IF;
    IF old_id IS DISTINCT FROM new_id THEN PERFORM sc_analytics_sync_member(old_id); END IF;
    PERFORM sc_analytics_sync_member(new_id);
    RETURN NULL;
END $$;

DO $$
DECLARE t text; k text; who uuid;
BEGIN
    FOR t,k IN SELECT * FROM (VALUES ('tbl_user','id'),('user_roles','user_id'),('tbl_listener-profile','user_id'),
      ('tbl_musician_profile','user_id'),('tbl_venues','owner_id'),('tbl_studio_profile','user_id'),
      ('tbl_organizer_profile','user_id'),('tbl_producer_profile','user_id')) v(tab,col) LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS sc_analytics_member ON %I',t);
        EXECUTE format('CREATE CONSTRAINT TRIGGER sc_analytics_member AFTER INSERT OR DELETE OR UPDATE OF %s ON %I
          DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION sc_analytics_member_trigger(%L)',
          CASE WHEN t='tbl_user' THEN 'status,email_verified,erased_at' WHEN t='user_roles' THEN 'role_id,user_id' ELSE quote_ident(k) END,t,k);
    END LOOP;
    INSERT INTO tbl_analytics_report_start VALUES (true,transaction_timestamp()) ON CONFLICT DO NOTHING;
    IF FOUND THEN
        FOR who IN SELECT id FROM tbl_user WHERE erased_at IS NULL LOOP PERFORM sc_analytics_sync_member(who,true); END LOOP;
    END IF;
END $$;
COMMIT;
