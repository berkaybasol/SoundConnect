-- Staging can roll back to the preceding binary and continue writing its GA4 aggregates.
-- SoundConnect aggregates start independently at the first observation by the new binary.
BEGIN;
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS tbl_analytics_live_start (
    singleton boolean PRIMARY KEY CHECK (singleton),
    started_at timestamptz NOT NULL
);
CREATE TABLE IF NOT EXISTS tbl_analytics_live_activity (
    minute_at timestamptz NOT NULL CHECK (minute_at = date_trunc('minute', minute_at)),
    audience varchar(16) NOT NULL CHECK (audience IN ('LISTENER','MUSICIAN','VENUE','STUDIO','GUEST')),
    actor_id uuid NOT NULL,
    PRIMARY KEY (minute_at, audience, actor_id)
);
CREATE TABLE IF NOT EXISTS tbl_analytics_live_tick (
    minute_at timestamptz PRIMARY KEY CHECK (minute_at = date_trunc('minute', minute_at))
);

CREATE TABLE IF NOT EXISTS tbl_analytics_sc_live_sample (
    sampled_at timestamptz NOT NULL,
    audience varchar(12) NOT NULL CHECK (audience IN ('ALL','LISTENER','MUSICIAN','VENUE','STUDIO','GUEST')),
    users_count int NOT NULL CHECK (users_count >= 0),
    PRIMARY KEY (sampled_at, audience)
);
CREATE TABLE IF NOT EXISTS tbl_analytics_sc_month_peak (
    month_start date NOT NULL,
    audience varchar(12) NOT NULL CHECK (audience IN ('ALL','LISTENER','MUSICIAN','VENUE','STUDIO','GUEST')),
    users_count int NOT NULL CHECK (users_count >= 0),
    peak_at timestamptz NOT NULL,
    samples int NOT NULL CHECK (samples > 0),
    PRIMARY KEY (month_start, audience)
);

-- Continue preserving all previously observed cohort data. Future enrollment and actions
-- require the grant current under the expanded notice.
CREATE OR REPLACE FUNCTION sc_analytics_enroll_week(who uuid) RETURNS void LANGUAGE sql AS $$
    INSERT INTO tbl_analytics_week_cohort(user_id,opened_at,profile_type)
    SELECT o.user_id,o.opened_at,m.profile_type FROM tbl_analytics_account_opening o
    JOIN tbl_analytics_membership_period m ON m.user_id=o.user_id AND m.state='ACTIVE'
    JOIN tbl_member_analytics_preference p ON p.user_id=o.user_id
    WHERE o.user_id=who AND p.decision='GRANTED' AND p.policy_version='2026-11-26-r3'
      AND p.changed_at < o.opened_at + interval '168 hours' AND now() < o.opened_at + interval '168 hours'
    ON CONFLICT (user_id) DO NOTHING
$$;

CREATE OR REPLACE FUNCTION sc_record_first_action(member_id uuid, action_name text,
        eligible_profiles text[], action_at timestamptz DEFAULT clock_timestamp())
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    consent_id uuid;
    account_profile text;
BEGIN
    IF member_id IS NULL OR EXISTS (SELECT FROM tbl_analytics_first_action WHERE user_id=member_id) THEN
        RETURN;
    END IF;
    SELECT p.analytics_user_id, c.profile_type INTO consent_id, account_profile
      FROM tbl_member_analytics_preference p
      JOIN tbl_user u ON u.id=p.user_id
      JOIN tbl_analytics_account_opening o ON o.user_id=p.user_id
      JOIN tbl_analytics_week_cohort c ON c.user_id=p.user_id
      JOIN tbl_analytics_membership_period m ON m.user_id=p.user_id AND m.state='ACTIVE' AND m.profile_type=c.profile_type
     WHERE p.user_id=member_id AND p.decision='GRANTED' AND p.policy_version='2026-11-26-r3'
       AND p.changed_at<=action_at AND u.status='ACTIVE' AND u.email_verified
       AND u.erased_at IS NULL AND NOT u.access_suspended
       AND o.opened_at<=action_at AND action_at<o.opened_at+interval '168 hours'
       AND c.profile_type=ANY(eligible_profiles)
       FOR SHARE OF p;
    IF consent_id IS NULL THEN RETURN; END IF;
    INSERT INTO tbl_analytics_first_action(user_id,analytics_user_id,profile_type,action_type,occurred_at)
    VALUES (member_id,consent_id,account_profile,action_name,action_at) ON CONFLICT (user_id) DO NOTHING;
    IF FOUND THEN
        UPDATE tbl_analytics_week_cohort SET first_action=action_name
        WHERE user_id=member_id AND first_action IS NULL;
    END IF;
END
$$;
COMMIT;
