BEGIN;
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS tbl_content_report (
    id uuid PRIMARY KEY,
    request_id uuid NOT NULL,
    reporter_id uuid REFERENCES tbl_user(id) ON DELETE SET NULL,
    kind varchar(40) NOT NULL CHECK (kind IN ('LISTENER_PROFILE','MUSICIAN_PROFILE','VENUE_PROFILE','STUDIO_PROFILE',
        'OVERTHINKING_POST','COMMENT','EVENT_PROFILE_SHARE','OVERTHINKING_PROFILE_SHARE','TABLEGROUP_PROFILE_SHARE')),
    target_id uuid,
    profile_id uuid,
    reason varchar(20) NOT NULL CHECK (reason IN ('IMPERSONATION','HARASSMENT','INAPPROPRIATE','PRIVACY','SPAM','OTHER')),
    description varchar(500),
    target_label text NOT NULL,
    evidence_text text,
    parent_text text,
    reported_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    status varchar(16) NOT NULL DEFAULT 'OPEN',
    decision varchar(20),
    resolution_note varchar(500),
    reviewed_at timestamptz,
    reviewer_id uuid REFERENCES tbl_user(id) ON DELETE SET NULL,
    version bigint NOT NULL DEFAULT 0,
    UNIQUE (reporter_id, request_id),
    CHECK ((status='OPEN' AND decision IS NULL AND reviewed_at IS NULL AND resolution_note IS NULL AND version=0) OR
        (status='RESOLVED' AND decision IS NOT NULL AND decision IN ('DISMISS','CONFIRM_VIOLATION','REMOVE_CONTENT')
         AND reviewed_at IS NOT NULL AND resolution_note IS NOT NULL AND version=1))
);
CREATE INDEX IF NOT EXISTS idx_content_report_queue ON tbl_content_report(status,reported_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_content_report_kind_queue ON tbl_content_report(status,kind,reported_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS idx_content_report_actor_time ON tbl_content_report(reporter_id,reported_at DESC);
CREATE INDEX IF NOT EXISTS idx_content_report_profile ON tbl_content_report(target_id)
    WHERE kind IN ('LISTENER_PROFILE','MUSICIAN_PROFILE','VENUE_PROFILE','STUDIO_PROFILE');
CREATE INDEX IF NOT EXISTS idx_content_report_reviewer ON tbl_content_report(reviewer_id) WHERE reviewer_id IS NOT NULL;

CREATE OR REPLACE FUNCTION soundconnect_unlink_content_reports() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE tbl_content_report SET reporter_id=NULL WHERE reporter_id=OLD.id;
    UPDATE tbl_content_report SET reviewer_id=NULL WHERE reviewer_id=OLD.id;
    UPDATE tbl_content_report SET target_id=NULL,profile_id=NULL,target_label='Silinmiş profil',evidence_text=NULL
        WHERE target_id=OLD.id AND kind IN ('LISTENER_PROFILE','MUSICIAN_PROFILE','VENUE_PROFILE','STUDIO_PROFILE');
    RETURN NULL;
END $$;
DROP TRIGGER IF EXISTS tr_content_report_erased ON tbl_user;
CREATE TRIGGER tr_content_report_erased AFTER UPDATE OF erased_at ON tbl_user
    FOR EACH ROW WHEN (OLD.erased_at IS NULL AND NEW.erased_at IS NOT NULL)
    EXECUTE FUNCTION soundconnect_unlink_content_reports();
DROP TRIGGER IF EXISTS tr_content_report_deleted ON tbl_user;
CREATE TRIGGER tr_content_report_deleted AFTER DELETE ON tbl_user FOR EACH ROW
    EXECUTE FUNCTION soundconnect_unlink_content_reports();

INSERT INTO tbl_permissions(id,name,created_at,updated_at)
VALUES ('e457767b-91d7-44b9-b7f0-d1759a25f40c','MANAGE_CONTENT_REPORTS',now(),now())
ON CONFLICT(name) DO NOTHING;
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM tbl_role r CROSS JOIN tbl_permissions p
WHERE r.name IN ('ROLE_ADMIN','ROLE_OWNER') AND p.name='MANAGE_CONTENT_REPORTS'
  AND NOT EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);

COMMIT;
