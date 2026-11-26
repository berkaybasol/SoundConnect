-- Members without a decision have no preference row or measurement identifier.
BEGIN;
SET LOCAL lock_timeout = '10s';

CREATE TABLE IF NOT EXISTS tbl_member_analytics_preference (
    user_id uuid PRIMARY KEY REFERENCES tbl_user(id) ON DELETE CASCADE,
    decision varchar(8) NOT NULL CHECK (decision IN ('GRANTED', 'DENIED')),
    policy_version varchar(32) NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    changed_at timestamptz NOT NULL,
    analytics_user_id uuid UNIQUE,
    CHECK ((decision = 'GRANTED') = (analytics_user_id IS NOT NULL))
);

-- Account erasure retains a tombstone for shared history. Remove the preference
-- in the same transaction, including for accounts erased by a previous binary.
CREATE OR REPLACE FUNCTION soundconnect_erase_member_analytics_preference()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    DELETE FROM tbl_member_analytics_preference WHERE user_id = NEW.id;
    RETURN NULL;
END
$$;

DROP TRIGGER IF EXISTS erase_member_analytics_preference ON tbl_user;
CREATE TRIGGER erase_member_analytics_preference AFTER UPDATE OF erased_at ON tbl_user
    FOR EACH ROW WHEN (OLD.erased_at IS NULL AND NEW.erased_at IS NOT NULL)
    EXECUTE FUNCTION soundconnect_erase_member_analytics_preference();
COMMIT;
