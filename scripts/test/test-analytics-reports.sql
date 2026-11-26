\set ON_ERROR_STOP on
-- Run in an empty, disposable PostgreSQL database.
CREATE TABLE tbl_user(id uuid PRIMARY KEY, erased_at timestamptz, status text, email_verified boolean);
CREATE TABLE tbl_role(id uuid PRIMARY KEY, name text);
CREATE TABLE user_roles(user_id uuid,role_id uuid);
CREATE TABLE "tbl_listener-profile"(user_id uuid);
CREATE TABLE tbl_musician_profile(user_id uuid);
CREATE TABLE tbl_venues(owner_id uuid);
CREATE TABLE tbl_studio_profile(user_id uuid);
CREATE TABLE tbl_organizer_profile(user_id uuid);
CREATE TABLE tbl_producer_profile(user_id uuid);
CREATE TABLE tbl_member_analytics_preference(user_id uuid,decision text,policy_version text,changed_at timestamptz);
INSERT INTO tbl_user VALUES ('00000000-0000-4000-8000-000000000001',null,'ACTIVE',true);
INSERT INTO "tbl_listener-profile" SELECT id FROM tbl_user;
\ir ../db/2026-11-26-analytics-reports.sql
\ir ../db/2026-11-26-analytics-reports.sql
DO $$ BEGIN
 ASSERT (SELECT count(*) FROM tbl_analytics_membership_period WHERE state='ACTIVE' AND NOT new_member)=1;
 ASSERT (SELECT count(*) FROM tbl_analytics_account_opening)=0;
END $$;
INSERT INTO tbl_user VALUES('00000000-0000-4000-8000-000000000002',null,'PENDING_STUDIO_REQUEST',true);
BEGIN;
UPDATE tbl_user SET status='ACTIVE' WHERE id='00000000-0000-4000-8000-000000000002';
INSERT INTO tbl_studio_profile VALUES('00000000-0000-4000-8000-000000000002');
COMMIT;
INSERT INTO tbl_member_analytics_preference VALUES('00000000-0000-4000-8000-000000000002','GRANTED','2026-11-26-r2',now());
DO $$ BEGIN
 ASSERT (SELECT count(*) FROM tbl_analytics_membership_period WHERE profile_type='STUDIO' AND state='PENDING' AND ended_at IS NOT NULL)=1;
 ASSERT (SELECT count(*) FROM tbl_analytics_membership_period WHERE profile_type='STUDIO' AND new_member AND ended_at IS NULL)=1;
 ASSERT (SELECT count(*) FROM tbl_analytics_week_cohort WHERE profile_type='STUDIO')=1;
END $$;
UPDATE tbl_user SET erased_at=now() WHERE id='00000000-0000-4000-8000-000000000002';
DO $$ BEGIN
 ASSERT (SELECT count(*) FROM tbl_analytics_membership_period WHERE profile_type='STUDIO' AND erased AND user_id IS NULL AND new_member)=1;
 ASSERT (SELECT count(*) FROM tbl_analytics_week_cohort WHERE user_id IS NULL)=1;
 ASSERT (SELECT count(*) FROM tbl_analytics_account_opening)=0;
END $$;
