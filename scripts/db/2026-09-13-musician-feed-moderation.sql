-- Administrator workflow for the immutable generic musician-feed report queue.
-- Prerequisite: 2026-09-11-musician-feed-delivery.sql. Run with ON_ERROR_STOP=1.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE public.tbl_musician_feed_content_report
    ADD COLUMN IF NOT EXISTS version bigint NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS review_decision varchar(24),
    ADD COLUMN IF NOT EXISTS reviewed_by_user_id uuid,
    ADD COLUMN IF NOT EXISTS reviewed_at timestamptz,
    ADD COLUMN IF NOT EXISTS resolution_note varchar(500);
UPDATE public.tbl_musician_feed_content_report SET version=0 WHERE version IS NULL;
ALTER TABLE public.tbl_musician_feed_content_report
    ALTER COLUMN version SET DEFAULT 0,
    ALTER COLUMN version SET NOT NULL;

ALTER TABLE public.tbl_musician_feed_content_report
    DROP CONSTRAINT IF EXISTS ck_musician_feed_report_status,
    ADD CONSTRAINT ck_musician_feed_report_status CHECK
        (status IN ('NEW','REVIEWING','DISMISSED','ACTIONED','RESTORED')),
    DROP CONSTRAINT IF EXISTS ck_musician_feed_report_review_state,
    ADD CONSTRAINT ck_musician_feed_report_review_state CHECK (
        version >= 0 AND (
            -- Preserve legacy status/evidence without inventing a past reviewer.
            (version=0 AND review_decision IS NULL AND reviewed_by_user_id IS NULL
                AND reviewed_at IS NULL AND resolution_note IS NULL)
            OR (version>0 AND review_decision IS NOT NULL AND reviewed_by_user_id IS NOT NULL AND reviewed_at IS NOT NULL
                AND resolution_note IS NOT NULL AND char_length(btrim(resolution_note)) BETWEEN 5 AND 500
                AND ((status='REVIEWING' AND review_decision='START_REVIEW')
                    OR (status='DISMISSED' AND review_decision='DISMISS')
                    OR (status='ACTIONED' AND review_decision='REMOVE_FROM_FEED')
                    OR (status='RESTORED' AND review_decision='RESTORE_TO_FEED')))
        ));

CREATE INDEX IF NOT EXISTS idx_musician_feed_report_type_queue
    ON public.tbl_musician_feed_content_report(status,item_type,reported_at DESC,id DESC);

CREATE TABLE IF NOT EXISTS public.tbl_musician_feed_report_audit (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL,
    client_request_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    resulting_version bigint NOT NULL,
    decision varchar(24) NOT NULL,
    previous_status varchar(24) NOT NULL,
    status varchar(24) NOT NULL,
    actor_user_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    resolution_note varchar(500) NOT NULL
);

DO $audit_constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.tbl_musician_feed_report_audit'::regclass
            AND conname='fk_musician_feed_report_audit_report') THEN
        ALTER TABLE public.tbl_musician_feed_report_audit ADD CONSTRAINT fk_musician_feed_report_audit_report
            FOREIGN KEY(report_id) REFERENCES public.tbl_musician_feed_content_report(id) ON DELETE CASCADE;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.tbl_musician_feed_report_audit'::regclass
            AND conname='uk_musician_feed_report_audit_request') THEN
        ALTER TABLE public.tbl_musician_feed_report_audit ADD CONSTRAINT uk_musician_feed_report_audit_request
            UNIQUE(report_id,client_request_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='public.tbl_musician_feed_report_audit'::regclass
            AND conname='uk_musician_feed_report_audit_version') THEN
        ALTER TABLE public.tbl_musician_feed_report_audit ADD CONSTRAINT uk_musician_feed_report_audit_version
            UNIQUE(report_id,resulting_version);
    END IF;
END
$audit_constraints$;

ALTER TABLE public.tbl_musician_feed_report_audit
    DROP CONSTRAINT IF EXISTS ck_musician_feed_report_audit_transition,
    ADD CONSTRAINT ck_musician_feed_report_audit_transition CHECK (
        resulting_version>0 AND request_hash ~ '^[0-9a-f]{64}$'
        AND char_length(btrim(resolution_note)) BETWEEN 5 AND 500
        AND ((previous_status='NEW' AND status='REVIEWING' AND decision='START_REVIEW')
            OR (previous_status IN ('NEW','REVIEWING') AND status='DISMISSED' AND decision='DISMISS')
            OR (previous_status IN ('NEW','REVIEWING') AND status='ACTIONED' AND decision='REMOVE_FROM_FEED')
            OR (previous_status='ACTIONED' AND status='RESTORED' AND decision='RESTORE_TO_FEED')));

-- A report's erasure must not unhide content for everyone. These report/actor
-- UUIDs are logical audit identities, deliberately without cascading foreign keys.
CREATE TABLE IF NOT EXISTS public.tbl_musician_feed_restriction (
    report_id uuid PRIMARY KEY,
    scope_key varchar(320) NOT NULL,
    active boolean NOT NULL,
    orphaned boolean NOT NULL DEFAULT false,
    applied_by_user_id uuid NOT NULL,
    applied_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
ALTER TABLE public.tbl_musician_feed_restriction
    ADD COLUMN IF NOT EXISTS orphaned boolean NOT NULL DEFAULT false;
UPDATE public.tbl_musician_feed_restriction restriction SET orphaned=true
WHERE NOT restriction.orphaned AND NOT EXISTS (
    SELECT 1 FROM public.tbl_musician_feed_content_report report WHERE report.id=restriction.report_id);

-- Report deletion (including reporter erasure through ON DELETE CASCADE) only
-- marks its own restriction. The queue can then use a bounded partial index.
CREATE OR REPLACE FUNCTION public.mark_musician_feed_restriction_orphaned()
RETURNS trigger LANGUAGE plpgsql AS $orphan_marker$
BEGIN
    UPDATE public.tbl_musician_feed_restriction SET orphaned=true WHERE report_id=OLD.id;
    RETURN OLD;
END
$orphan_marker$;
DROP TRIGGER IF EXISTS trg_musician_feed_report_restriction_orphaned
    ON public.tbl_musician_feed_content_report;
CREATE TRIGGER trg_musician_feed_report_restriction_orphaned
    AFTER DELETE ON public.tbl_musician_feed_content_report
    FOR EACH ROW EXECUTE FUNCTION public.mark_musician_feed_restriction_orphaned();
CREATE INDEX IF NOT EXISTS idx_musician_feed_restriction_scope
    ON public.tbl_musician_feed_restriction(scope_key,active,report_id);
ALTER TABLE public.tbl_musician_feed_restriction
    DROP CONSTRAINT IF EXISTS ck_musician_feed_restriction_scope,
    ADD CONSTRAINT ck_musician_feed_restriction_scope CHECK (btrim(scope_key)<>'' AND char_length(scope_key)<=320);

CREATE INDEX IF NOT EXISTS idx_musician_feed_restriction_orphan_queue
    ON public.tbl_musician_feed_restriction(active,applied_at DESC,report_id DESC) WHERE orphaned;
CREATE TABLE IF NOT EXISTS public.tbl_musician_feed_restriction_restore_audit (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL,
    client_request_id uuid NOT NULL,
    request_hash varchar(64) NOT NULL,
    actor_user_id uuid NOT NULL,
    resolution_note varchar(500) NOT NULL,
    restored_at timestamptz NOT NULL
);
DO $orphan_audit_constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid='public.tbl_musician_feed_restriction_restore_audit'::regclass
            AND conname='uk_musician_feed_restriction_restore_request') THEN
        ALTER TABLE public.tbl_musician_feed_restriction_restore_audit
            ADD CONSTRAINT uk_musician_feed_restriction_restore_request UNIQUE(report_id,client_request_id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
            WHERE conrelid='public.tbl_musician_feed_restriction_restore_audit'::regclass
            AND conname='uk_musician_feed_restriction_restore_report') THEN
        ALTER TABLE public.tbl_musician_feed_restriction_restore_audit
            ADD CONSTRAINT uk_musician_feed_restriction_restore_report UNIQUE(report_id);
    END IF;
END
$orphan_audit_constraints$;
ALTER TABLE public.tbl_musician_feed_restriction_restore_audit
    DROP CONSTRAINT IF EXISTS ck_musician_feed_restriction_restore_audit,
    ADD CONSTRAINT ck_musician_feed_restriction_restore_audit CHECK (
        request_hash ~ '^[0-9a-f]{64}$' AND char_length(btrim(resolution_note)) BETWEEN 5 AND 500);

DO $moderation_permission$
BEGIN
    IF to_regclass('public.tbl_permissions') IS NULL OR to_regclass('public.tbl_role') IS NULL
            OR to_regclass('public.role_permissions') IS NULL THEN RETURN; END IF;
    INSERT INTO public.tbl_permissions(id,name,created_at,updated_at)
    VALUES ('cbe053ef-e8f9-4d9b-91d7-e325355713bf','MANAGE_MUSICIAN_FEED_REPORTS',now(),now())
    ON CONFLICT(name) DO NOTHING;
    INSERT INTO public.role_permissions(role_id,permission_id)
    SELECT role.id,permission.id FROM public.tbl_role role JOIN public.tbl_permissions permission
        ON permission.name='MANAGE_MUSICIAN_FEED_REPORTS'
    WHERE role.name IN ('ROLE_ADMIN','ROLE_OWNER') AND NOT EXISTS (
        SELECT 1 FROM public.role_permissions existing
        WHERE existing.role_id=role.id AND existing.permission_id=permission.id);
END
$moderation_permission$;

CREATE TABLE IF NOT EXISTS public.soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-13-musician-feed-moderation') ON CONFLICT DO NOTHING;
COMMIT;
