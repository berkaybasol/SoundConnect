-- Extend the existing Promotion/media/engagement model; no application data is seeded.
-- Apply with psql ON_ERROR_STOP=1 before enabling announcement endpoints.
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';

ALTER TABLE tlb_promotion ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0;
ALTER TABLE tlb_promotion ADD COLUMN IF NOT EXISTS first_published_at timestamptz;
ALTER TABLE tlb_promotion ADD COLUMN IF NOT EXISTS archived_at timestamptz;
ALTER TABLE tlb_promotion ADD COLUMN IF NOT EXISTS created_by uuid;
ALTER TABLE tlb_promotion ADD COLUMN IF NOT EXISTS updated_by uuid;
ALTER TABLE tlb_promotion ALTER COLUMN description TYPE varchar(5000);
ALTER TABLE tlb_promotion ALTER COLUMN media_asset_id DROP NOT NULL;

CREATE TABLE IF NOT EXISTS tbl_promotion_audience (
    promotion_id uuid NOT NULL REFERENCES tlb_promotion(id) ON DELETE CASCADE,
    profile_type varchar(30) NOT NULL,
    CONSTRAINT uq_promotion_audience UNIQUE (promotion_id, profile_type),
    CONSTRAINT ck_promotion_audience_profile CHECK (profile_type IN ('MUSICIAN','LISTENER','VENUE','STUDIO'))
);

-- Hibernate-generated enum checks have installation-dependent names. Replace only
-- checks on the exact single enum column; preserve all unrelated business checks.
DO $announcement_enums$
DECLARE item record; enum_check record;
BEGIN
    FOR item IN SELECT * FROM (VALUES
        ('tlb_promotion','placement','ck_promotion_placement',
            $$placement IN ('VENUE_MANAGEMENT_PANEL','FEED')$$),
        ('tlb_promotion','status','ck_promotion_status',
            $$status IN ('DRAFT','ACTIVE','INACTIVE','EXPIRED','ARCHIVED')$$),
        ('tbl_media_asset','owner_type','ck_media_asset_owner_type',
            $$owner_type IN ('USER','BAND','VENUE','MUSICIAN_PROFILE','PRODUCER_PROFILE','ORGANIZER_PROFILE',
                'MUSIC_HOUSE_PROFILE','STUDIO_PROFILE','LISTENER_PROFILE','VENUE_PROFILE','MANAGER_PROFILE','PROMOTION')$$),
        ('tbl_like','target_type','ck_like_target_type',
            $$target_type IN ('OVERTHINKING','OVERTHINKING_PROFILE_SHARE','MEDIA','EVENT','EVENT_POST','TABLE_GROUP_POST','COMMENT','ANNOUNCEMENT')$$),
        ('tbl_comment','target_type','ck_comment_target_type',
            $$target_type IN ('OVERTHINKING','OVERTHINKING_PROFILE_SHARE','MEDIA','EVENT','EVENT_POST','TABLE_GROUP_POST','COMMENT','ANNOUNCEMENT')$$)
    ) AS definitions(table_name,column_name,check_name,expression)
    LOOP
        FOR enum_check IN
            SELECT constraint_definition.conname
            FROM pg_constraint constraint_definition
            JOIN pg_attribute attribute ON attribute.attrelid=constraint_definition.conrelid
                AND attribute.attnum=constraint_definition.conkey[1]
            WHERE constraint_definition.conrelid=to_regclass(item.table_name)
                AND constraint_definition.contype='c' AND cardinality(constraint_definition.conkey)=1
                AND attribute.attname=item.column_name
        LOOP
            EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I',item.table_name,enum_check.conname);
        END LOOP;
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (%s)',item.table_name,item.check_name,item.expression);
    END LOOP;
END $announcement_enums$;

ALTER TABLE tlb_promotion DROP CONSTRAINT IF EXISTS ck_promotion_feed_announcement;
ALTER TABLE tlb_promotion ADD CONSTRAINT ck_promotion_feed_announcement CHECK (
    placement<>'FEED' OR (type='ANNOUNCEMENT' AND description IS NOT NULL
        AND length(btrim(description))>0 AND redirect_url IS NULL
        AND (status<>'ACTIVE' OR (first_published_at IS NOT NULL AND start_date IS NOT NULL)))
);
ALTER TABLE tbl_media_asset DROP CONSTRAINT IF EXISTS ck_media_promotion_private;
ALTER TABLE tbl_media_asset ADD CONSTRAINT ck_media_promotion_private CHECK (
    owner_type<>'PROMOTION' OR (visibility='PRIVATE' AND kind IN ('IMAGE','VIDEO'))
);

CREATE INDEX IF NOT EXISTS ix_promotion_feed_published
    ON tlb_promotion(first_published_at DESC,id DESC) WHERE placement='FEED' AND type='ANNOUNCEMENT' AND status='ACTIVE';
CREATE INDEX IF NOT EXISTS ix_promotion_feed_admin
    ON tlb_promotion(created_at DESC,id DESC) WHERE placement='FEED' AND type='ANNOUNCEMENT';
CREATE INDEX IF NOT EXISTS ix_promotion_media_reference ON tlb_promotion(media_asset_id) WHERE media_asset_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_promotion_audience_profile ON tbl_promotion_audience(profile_type,promotion_id);

COMMIT;
