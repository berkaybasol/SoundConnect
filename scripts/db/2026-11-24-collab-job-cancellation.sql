BEGIN;

ALTER TABLE tbl_collab_job
    DROP CONSTRAINT IF EXISTS tbl_collab_job_status_check,
    DROP CONSTRAINT IF EXISTS ck_collab_job_status,
    DROP CONSTRAINT IF EXISTS ck_collab_job_lifecycle,
    ADD CONSTRAINT ck_collab_job_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    ADD CONSTRAINT ck_collab_job_lifecycle CHECK (
        (status IN ('ACTIVE', 'CANCELLED')
            AND completed_at IS NULL
            AND NOT (publisher_confirmed_at IS NOT NULL AND applicant_confirmed_at IS NOT NULL))
        OR (status = 'COMPLETED'
            AND publisher_confirmed_at IS NOT NULL AND applicant_confirmed_at IS NOT NULL
            AND completed_at IS NOT NULL
            AND completed_at >= publisher_confirmed_at AND completed_at >= applicant_confirmed_at)
    );

COMMIT;
