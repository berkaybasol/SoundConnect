-- SoundConnect Collab report moderation rollout.
--
-- Prerequisite: 2026-08-11-collab-domain.sql
-- Run with psql ON_ERROR_STOP=1. The migration is transactional and rerunnable.

BEGIN;

ALTER TABLE tbl_collab_report
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS status varchar(16) NOT NULL DEFAULT 'OPEN',
    ADD COLUMN IF NOT EXISTS review_decision varchar(24),
    ADD COLUMN IF NOT EXISTS reviewed_by_user_id uuid,
    ADD COLUMN IF NOT EXISTS reviewed_at timestamp with time zone,
    ADD COLUMN IF NOT EXISTS resolution_note varchar(500),
    ADD COLUMN IF NOT EXISTS listing_evidence jsonb;

-- Capture the best available report-time evidence for rows created before the
-- immutable snapshot column existed. New application writes populate this
-- column in the same transaction that creates the report.
UPDATE tbl_collab_report report
   SET listing_evidence = jsonb_build_object(
           'title', listing.title,
           'description', listing.description,
           'publisherActorId', listing.publisher_actor_id,
           'publisherDisplayName', publisher.display_name,
           'cadence', listing.cadence,
           'wantedType', listing.wanted_type,
           'instrumentId', instrument.id,
           'instrumentName', instrument.name,
           'branch', listing.branch,
           'customSpecialty', listing.custom_specialty,
           'cityId', city.id,
           'cityName', city.name,
           'genres', COALESCE((
               SELECT jsonb_agg(genre.genre ORDER BY genre.position)
                 FROM tbl_collab_genre genre
                WHERE genre.collab_id = listing.id
           ), '[]'::jsonb),
           'scheduledAt', listing.scheduled_at,
           'feeAmountMinor', listing.fee_amount_minor,
           'currency', listing.currency,
           'listingStatus', listing.status
       )
  FROM tbl_collab listing
  JOIN tbl_collab_actor publisher ON publisher.id = listing.publisher_actor_id
  JOIN tbl_city city ON city.id = listing.city_id
  LEFT JOIN tbl_instrument instrument ON instrument.id = listing.instrument_id
 WHERE report.collab_id = listing.id
   AND report.listing_evidence IS NULL;

DO $collab_report_evidence_required$
BEGIN
    IF EXISTS (SELECT 1 FROM tbl_collab_report WHERE listing_evidence IS NULL) THEN
        RAISE EXCEPTION
            'cannot enforce immutable Collab report evidence: at least one report could not be backfilled'
            USING ERRCODE = '23502';
    END IF;

    ALTER TABLE tbl_collab_report
        ALTER COLUMN listing_evidence SET NOT NULL;
END
$collab_report_evidence_required$;

DO $collab_report_moderation_constraints$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_report'::regclass
           AND conname = 'fk_collab_report_reviewer'
    ) THEN
        ALTER TABLE tbl_collab_report
            ADD CONSTRAINT fk_collab_report_reviewer
            FOREIGN KEY (reviewed_by_user_id)
            REFERENCES tbl_user (id)
            ON DELETE RESTRICT;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_report'::regclass
           AND conname = 'ck_collab_report_status'
    ) THEN
        ALTER TABLE tbl_collab_report
            ADD CONSTRAINT ck_collab_report_status
            CHECK (status IN ('OPEN', 'DISMISSED', 'ACTIONED'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_report'::regclass
           AND conname = 'ck_collab_report_review_decision'
    ) THEN
        ALTER TABLE tbl_collab_report
            ADD CONSTRAINT ck_collab_report_review_decision
            CHECK (
                review_decision IS NULL
                OR review_decision IN ('DISMISS', 'REMOVE_LISTING')
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_report'::regclass
           AND conname = 'ck_collab_report_resolution_note'
    ) THEN
        ALTER TABLE tbl_collab_report
            ADD CONSTRAINT ck_collab_report_resolution_note
            CHECK (
                resolution_note IS NULL
                OR char_length(btrim(resolution_note)) BETWEEN 5 AND 500
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_report'::regclass
           AND conname = 'ck_collab_report_review_state'
    ) THEN
        ALTER TABLE tbl_collab_report
            ADD CONSTRAINT ck_collab_report_review_state
            CHECK (
                (
                    status = 'OPEN'
                    AND review_decision IS NULL
                    AND reviewed_by_user_id IS NULL
                    AND reviewed_at IS NULL
                    AND resolution_note IS NULL
                )
                OR
                (
                    status = 'DISMISSED'
                    AND review_decision = 'DISMISS'
                    AND reviewed_by_user_id IS NOT NULL
                    AND reviewed_at IS NOT NULL
                    AND resolution_note IS NOT NULL
                )
                OR
                (
                    status = 'ACTIONED'
                    AND review_decision = 'REMOVE_LISTING'
                    AND reviewed_by_user_id IS NOT NULL
                    AND reviewed_at IS NOT NULL
                    AND resolution_note IS NOT NULL
                )
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_collab_report'::regclass
           AND conname = 'ck_collab_report_listing_evidence'
    ) THEN
        ALTER TABLE tbl_collab_report
            ADD CONSTRAINT ck_collab_report_listing_evidence
            CHECK (
                jsonb_typeof(listing_evidence) = 'object'
                AND listing_evidence ?& ARRAY[
                    'title', 'description', 'publisherActorId', 'publisherDisplayName',
                    'cadence', 'wantedType', 'instrumentId', 'instrumentName',
                    'branch', 'customSpecialty', 'cityId', 'cityName', 'genres',
                    'scheduledAt', 'feeAmountMinor', 'currency', 'listingStatus'
                ]::text[]
            );
    END IF;
END
$collab_report_moderation_constraints$;

CREATE INDEX IF NOT EXISTS idx_collab_report_admin_queue
    ON tbl_collab_report (status, reason, reported_at DESC, id DESC);

-- The role tables are absent in the isolated migration test fixture, so the
-- authority seed is deliberately conditional. Runtime bootstrap remains a
-- second idempotent safety net for development installations.
DO $collab_report_permission$
BEGIN
    IF to_regclass(format('%I.%I', current_schema(), 'tbl_permissions')) IS NULL
       OR to_regclass(format('%I.%I', current_schema(), 'tbl_role')) IS NULL
       OR to_regclass(format('%I.%I', current_schema(), 'role_permissions')) IS NULL THEN
        RETURN;
    END IF;

    EXECUTE $permission_insert$
        INSERT INTO tbl_permissions (id, name, created_at, updated_at)
        VALUES (
            'fe37e7bb-11df-44c1-8a42-b56897cb60fb',
            'MANAGE_COLLAB_REPORTS',
            CURRENT_TIMESTAMP,
            CURRENT_TIMESTAMP
        )
        ON CONFLICT (name) DO NOTHING
    $permission_insert$;

    EXECUTE $role_grant$
        INSERT INTO role_permissions (role_id, permission_id)
        SELECT role.id, permission.id
          FROM tbl_role role
          JOIN tbl_permissions permission
            ON permission.name = 'MANAGE_COLLAB_REPORTS'
         WHERE role.name IN ('ROLE_ADMIN', 'ROLE_OWNER')
           AND NOT EXISTS (
               SELECT 1
                 FROM role_permissions existing
                WHERE existing.role_id = role.id
                  AND existing.permission_id = permission.id
           )
        ON CONFLICT DO NOTHING
    $role_grant$;
END
$collab_report_permission$;

COMMIT;
