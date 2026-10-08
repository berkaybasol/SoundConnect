-- Apply after the marketplace domain migration and before enabling the feature.
BEGIN;
SET LOCAL lock_timeout='5s';
SET LOCAL statement_timeout='60s';

-- Replace only single-column enum checks, including installation-specific names.
DO $marketplace_owner_enum$
DECLARE enum_check record;
BEGIN
    FOR enum_check IN
        SELECT c.conname FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=c.conkey[1]
        WHERE c.conrelid='tbl_media_asset'::regclass AND c.contype='c'
            AND cardinality(c.conkey)=1 AND a.attname='owner_type'
    LOOP
        EXECUTE format('ALTER TABLE tbl_media_asset DROP CONSTRAINT %I',enum_check.conname);
    END LOOP;
END $marketplace_owner_enum$;

ALTER TABLE tbl_media_asset ADD CONSTRAINT ck_media_asset_owner_type CHECK (
    owner_type IN ('USER','BAND','VENUE','MUSICIAN_PROFILE','PRODUCER_PROFILE','ORGANIZER_PROFILE',
        'MUSIC_HOUSE_PROFILE','STUDIO_PROFILE','LISTENER_PROFILE','VENUE_PROFILE','MANAGER_PROFILE','PROMOTION','MARKETPLACE')
);
ALTER TABLE tbl_media_asset DROP CONSTRAINT IF EXISTS ck_media_marketplace_private;
ALTER TABLE tbl_media_asset ADD CONSTRAINT ck_media_marketplace_private CHECK (
    owner_type<>'MARKETPLACE' OR (visibility='PRIVATE' AND kind='IMAGE' AND content_audience='BACKSTAGE')
);
CREATE INDEX IF NOT EXISTS ix_marketplace_media_orphan
    ON tbl_media_asset(created_at,id) INCLUDE(owner_id)
    WHERE owner_type='MARKETPLACE' AND status='READY';
COMMIT;
