-- Capture only committed product transitions during an active member's consent epoch.
BEGIN;
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS tbl_analytics_first_action (
    user_id uuid PRIMARY KEY REFERENCES tbl_member_analytics_preference(user_id) ON DELETE CASCADE,
    analytics_user_id uuid,
    profile_type varchar(16) NOT NULL CHECK (profile_type IN ('LISTENER','MUSICIAN','VENUE','STUDIO')),
    action_type varchar(40) NOT NULL,
    occurred_at timestamptz NOT NULL,
    ga_claimed_at timestamptz
);

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
     WHERE p.user_id=member_id AND p.decision='GRANTED' AND p.policy_version='2026-11-26-r2'
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

-- Stop delivery as soon as a grant is withdrawn. The anonymous cohort's already
-- observed first action remains stable and the former GA identity is discarded.
CREATE OR REPLACE FUNCTION sc_retire_first_action_delivery() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE tbl_analytics_first_action SET analytics_user_id=NULL WHERE user_id=NEW.user_id;
    RETURN NULL;
END
$$;
DROP TRIGGER IF EXISTS retire_first_action_delivery ON tbl_member_analytics_preference;
CREATE TRIGGER retire_first_action_delivery AFTER UPDATE OF analytics_user_id ON tbl_member_analytics_preference
    FOR EACH ROW WHEN (OLD.analytics_user_id IS NOT NULL AND OLD.analytics_user_id IS DISTINCT FROM NEW.analytics_user_id)
    EXECUTE FUNCTION sc_retire_first_action_delivery();

CREATE OR REPLACE FUNCTION sc_profile_media_action(profile_kind text, subject uuid, asset_kind text)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE member_id uuid;
BEGIN
    IF profile_kind='MUSICIAN' AND asset_kind IN ('AUDIO','VIDEO') THEN
        SELECT user_id INTO member_id FROM tbl_musician_profile WHERE id=subject;
    ELSIF profile_kind='VENUE' AND asset_kind IN ('IMAGE','VIDEO') THEN
        SELECT v.owner_id INTO member_id FROM tbl_venues v WHERE v.id=subject OR v.id=(
            SELECT vp.venue_id FROM tbl_venue_profile vp WHERE vp.id=subject);
    ELSE RETURN;
    END IF;
    PERFORM sc_record_first_action(member_id,'profile_media_uploaded',ARRAY[profile_kind]);
END
$$;

CREATE OR REPLACE FUNCTION sc_first_action_product_transition() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    personal constant text[] := ARRAY['LISTENER','MUSICIAN'];
    publishers constant text[] := ARRAY['MUSICIAN','VENUE','STUDIO'];
    member_id uuid;
    media_row record;
BEGIN
    CASE TG_TABLE_NAME
    WHEN 'tbl_table_group' THEN
        PERFORM sc_record_first_action(NEW.owner_id,'table_created',personal);
    WHEN 'tbl_table_group_participants' THEN
        IF NEW.status='PENDING' AND NEW.application_id IS NOT NULL THEN
            -- The aggregate can rewrite collection rows. Preserve the original application time.
            PERFORM sc_record_first_action(NEW.user_id,'table_join_requested',personal,NEW.joined_at);
        END IF;
    WHEN 'tbl_overthinking_post' THEN
        PERFORM sc_record_first_action(NEW.author_id,'overthinking_published',personal);
    WHEN 'tbl_comment' THEN
        IF NOT NEW.is_deleted THEN PERFORM sc_record_first_action(NEW.user_id,'comment_published',personal); END IF;
    WHEN 'tbl_overthinking_profile_share','tbl_table_group_profile_share' THEN
        PERFORM sc_record_first_action(NEW.owner_user_id,'profile_share_published',ARRAY['LISTENER']);
    WHEN 'tbl_event_audience_intent' THEN
        IF NEW.published_on_profile AND (TG_OP='INSERT' OR NOT OLD.published_on_profile) THEN
            PERFORM sc_record_first_action(NEW.user_id,'profile_share_published',ARRAY['LISTENER']);
        END IF;
    WHEN 'tbl_band_member' THEN
        IF NEW.status='ACTIVE' AND (TG_OP='INSERT' OR OLD.status IS DISTINCT FROM 'ACTIVE') THEN
            PERFORM sc_record_first_action(NEW.user_id,
                CASE WHEN TG_OP='INSERT' AND NEW.band_role='FOUNDER' THEN 'band_created' ELSE 'band_joined' END,
                ARRAY['MUSICIAN']);
        END IF;
    WHEN 'artist_venue_connection_requests' THEN
        IF NEW.status='ACCEPTED' AND (TG_OP='INSERT' OR OLD.status IS DISTINCT FROM 'ACCEPTED') THEN
            SELECT owner_id INTO member_id FROM tbl_venues WHERE id=NEW.venue_id;
            IF NEW.musician_profile_id IS NOT NULL THEN
                PERFORM sc_record_first_action(member_id,'venue_musician_connected',ARRAY['VENUE']);
                SELECT user_id INTO member_id FROM tbl_musician_profile WHERE id=NEW.musician_profile_id;
                PERFORM sc_record_first_action(member_id,'venue_musician_connected',ARRAY['MUSICIAN']);
            ELSIF NEW.band_id IS NOT NULL THEN
                PERFORM sc_record_first_action(member_id,'venue_band_connected',ARRAY['VENUE']);
            END IF;
        END IF;
    WHEN 'tbl_collab','tbl_marketplace_listing' THEN
        IF NEW.published_at IS NOT NULL AND (TG_OP='INSERT' OR OLD.published_at IS NULL) THEN
            IF TG_TABLE_NAME='tbl_collab' AND NEW.status='OPEN' THEN
                PERFORM sc_record_first_action(NEW.owner_user_id,'collab_published',publishers);
            ELSIF TG_TABLE_NAME='tbl_marketplace_listing' AND NEW.status='PUBLISHED' THEN
                PERFORM sc_record_first_action(NEW.owner_user_id,'market_listing_published',publishers);
            END IF;
        END IF;
    WHEN 'tbl_collab_application' THEN
        IF NEW.status='PENDING' THEN PERFORM sc_record_first_action(NEW.applicant_user_id,'collab_applied',ARRAY['MUSICIAN']); END IF;
    WHEN 'tbl_event' THEN
        IF NEW.venue_calendar_approved AND (TG_OP='INSERT' OR NOT OLD.venue_calendar_approved) THEN
            SELECT owner_id INTO member_id FROM tbl_venues WHERE id=NEW.venue_id;
            PERFORM sc_record_first_action(member_id,'venue_event_published',ARRAY['VENUE']);
        END IF;
    WHEN 'tbl_studio_room','tbl_studio_equipment' THEN
        SELECT user_id INTO member_id FROM tbl_studio_profile WHERE id=NEW.studio_profile_id;
        PERFORM sc_record_first_action(member_id,
            CASE TG_TABLE_NAME WHEN 'tbl_studio_room' THEN 'studio_room_created' ELSE 'backline_equipment_added' END,
            ARRAY['STUDIO']);
    WHEN 'tbl_tracks' THEN
        IF NEW.owner_type='MUSICIAN_PROFILE' THEN
            PERFORM sc_profile_media_action('MUSICIAN',NEW.owner_id,'AUDIO');
        END IF;
    WHEN 'tbl_venue_profile' THEN
        IF NEW.profile_picture_media_id IS NOT NULL AND
              (TG_OP='INSERT' OR NEW.profile_picture_media_id IS DISTINCT FROM OLD.profile_picture_media_id) THEN
            PERFORM sc_profile_media_action('VENUE',NEW.venue_id,'IMAGE');
        END IF;
    WHEN 'tbl_profile_media' THEN
        SELECT kind INTO media_row FROM tbl_media_asset WHERE id=NEW.media_asset_id AND status='READY';
        IF FOUND THEN PERFORM sc_profile_media_action(NEW.profile_type,NEW.profile_id,media_row.kind); END IF;
    END CASE;
    RETURN NULL;
END
$$;

DO $$
DECLARE t text; columns_to_watch text;
BEGIN
    FOR t,columns_to_watch IN SELECT * FROM (VALUES
        ('tbl_table_group',NULL),('tbl_table_group_participants','status, application_id'),
        ('tbl_overthinking_post',NULL),('tbl_comment',NULL),('tbl_overthinking_profile_share',NULL),
        ('tbl_table_group_profile_share',NULL),('tbl_event_audience_intent','published_on_profile'),
        ('tbl_band_member','status'),('artist_venue_connection_requests','status'),
        ('tbl_collab','status, published_at'),('tbl_marketplace_listing','status, published_at'),
        ('tbl_collab_application',NULL),('tbl_event','venue_calendar_approved'),('tbl_studio_room',NULL),
        ('tbl_studio_equipment',NULL),('tbl_tracks',NULL),('tbl_venue_profile','profile_picture_media_id'),
        ('tbl_profile_media',NULL)) AS triggers(rel,cols) LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS analytics_first_action ON %I',t);
        EXECUTE format('CREATE TRIGGER analytics_first_action AFTER %s ON %I FOR EACH ROW EXECUTE FUNCTION sc_first_action_product_transition()',
            CASE WHEN columns_to_watch IS NULL THEN 'INSERT'
                 ELSE 'INSERT OR UPDATE OF '||columns_to_watch END,t);
    END LOOP;
END
$$;

-- Video completion runs under the isolated transcoder login. A fixed trigger
-- looks up this single completed asset without granting the worker access to
-- profile, account, consent, or analytics tables.
CREATE OR REPLACE FUNCTION sc_completed_profile_video() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public,pg_temp AS $$
DECLARE media_row record;
BEGIN
    FOR media_row IN SELECT profile_type,profile_id FROM public.tbl_profile_media WHERE media_asset_id=NEW.id LOOP
        PERFORM public.sc_profile_media_action(media_row.profile_type,media_row.profile_id,'VIDEO');
    END LOOP;
    RETURN NULL;
END
$$;
REVOKE ALL ON FUNCTION sc_completed_profile_video() FROM PUBLIC;
DROP TRIGGER IF EXISTS analytics_first_action ON tbl_media_asset;
CREATE TRIGGER analytics_first_action AFTER UPDATE OF status ON tbl_media_asset
    FOR EACH ROW WHEN (NEW.status='READY' AND OLD.status IS DISTINCT FROM 'READY' AND NEW.kind='VIDEO')
    EXECUTE FUNCTION sc_completed_profile_video();
COMMIT;
