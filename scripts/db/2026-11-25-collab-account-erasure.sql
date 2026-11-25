BEGIN;

-- A live application still requires a normalized phone at submission. A retained
-- application can lose that personal contact when its applicant erases their account.
ALTER TABLE tbl_collab_application ALTER COLUMN phone_snapshot DROP NOT NULL;

CREATE OR REPLACE FUNCTION soundconnect_purge_collab_account(account_id uuid)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    changed_at timestamptz;
BEGIN
    -- Creates, applications and saves take the same account lock before writing.
    PERFORM id FROM tbl_user WHERE id = account_id FOR NO KEY UPDATE;
    -- Accept/withdraw/expiry take the listing before its applications. Two
    -- accounts may have applied to each other's listings. Match the multi-listing
    -- expiration queries' (expires_at, id) order when the same rows are due.
    PERFORM id FROM tbl_collab WHERE id IN (
        SELECT id FROM tbl_collab WHERE owner_user_id = account_id
        UNION ALL
        SELECT collab_id FROM tbl_collab_application WHERE applicant_user_id = account_id
    ) ORDER BY expires_at, id FOR NO KEY UPDATE;
    changed_at := clock_timestamp();

    DELETE FROM tbl_collab_saved_listing saved USING (
        SELECT id FROM tbl_collab_saved_listing
        WHERE user_id = account_id OR collab_id IN (SELECT id FROM tbl_collab WHERE owner_user_id = account_id)
        ORDER BY id FOR UPDATE
    ) removed WHERE saved.id = removed.id;

    UPDATE tbl_collab_application AS app
    SET phone_snapshot = CASE WHEN app.applicant_user_id = account_id THEN NULL ELSE app.phone_snapshot END,
        status = CASE
            WHEN app.status <> 'PENDING' THEN app.status
            WHEN listing.owner_user_id = account_id THEN 'INVALIDATED_BY_LISTING_CLOSURE'
            ELSE 'WITHDRAWN_BY_APPLICANT'
        END,
        status_changed_at = CASE WHEN app.status = 'PENDING'
            THEN greatest(changed_at, app.submitted_at) ELSE app.status_changed_at END,
        updated_at = changed_at AT TIME ZONE 'UTC', version = app.version + 1
    FROM tbl_collab listing
    WHERE listing.id = app.collab_id AND (
        (listing.owner_user_id = account_id AND app.status = 'PENDING')
        OR (app.applicant_user_id = account_id AND (app.phone_snapshot IS NOT NULL OR app.status = 'PENDING'))
    );

    UPDATE tbl_collab_job SET status = 'CANCELLED',
        updated_at = changed_at AT TIME ZONE 'UTC', version = version + 1
    WHERE (publisher_user_id = account_id OR applicant_user_id = account_id) AND status = 'ACTIVE';

    UPDATE tbl_collab SET status = 'CLOSED', closure_reason = 'OWNER_CLOSED',
        closed_at = greatest(changed_at, published_at),
        updated_at = changed_at AT TIME ZONE 'UTC', version = version + 1
    WHERE owner_user_id = account_id AND status = 'OPEN';

    -- Unpublished drafts have no history or participants.
    DELETE FROM tbl_collab_genre WHERE collab_id IN
        (SELECT id FROM tbl_collab WHERE owner_user_id = account_id AND status = 'DRAFT');
    DELETE FROM tbl_collab WHERE owner_user_id = account_id AND status = 'DRAFT';
END;
$$;

-- Apply the same lifecycle to any retained Collab data from earlier erasures.
DO $$
DECLARE
    erased_id uuid;
BEGIN
    FOR erased_id IN SELECT u.id FROM tbl_user u WHERE u.erased_at IS NOT NULL AND (
        EXISTS (SELECT 1 FROM tbl_collab WHERE owner_user_id = u.id AND status IN ('OPEN', 'DRAFT'))
        OR EXISTS (SELECT 1 FROM tbl_collab_application WHERE applicant_user_id = u.id
            AND (phone_snapshot IS NOT NULL OR status = 'PENDING'))
        OR EXISTS (SELECT 1 FROM tbl_collab_application a JOIN tbl_collab l ON l.id = a.collab_id
            WHERE l.owner_user_id = u.id AND a.status = 'PENDING')
        OR EXISTS (SELECT 1 FROM tbl_collab_job WHERE (publisher_user_id = u.id OR applicant_user_id = u.id)
            AND status = 'ACTIVE')
        OR EXISTS (SELECT 1 FROM tbl_collab_saved_listing s JOIN tbl_collab l ON l.id = s.collab_id
            WHERE s.user_id = u.id OR l.owner_user_id = u.id)
    ) ORDER BY u.id LOOP
        PERFORM soundconnect_purge_collab_account(erased_id);
    END LOOP;
END;
$$;

ALTER TABLE tbl_collab_application
    DROP CONSTRAINT IF EXISTS ck_collab_pending_application_phone,
    ADD CONSTRAINT ck_collab_pending_application_phone CHECK (status <> 'PENDING' OR phone_snapshot IS NOT NULL);

COMMIT;
