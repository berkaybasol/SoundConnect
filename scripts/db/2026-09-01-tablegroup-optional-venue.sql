-- Makes TableGroup venue association optional while preserving registered and
-- custom venue validation. This is a compatibility migration for databases
-- that already installed the earlier ck_table_group_venue_shape definition.
-- Run with psql -v ON_ERROR_STOP=1 after tablegroup-description.

BEGIN;

DO $$
BEGIN
    IF to_regclass('tbl_table_group') IS NULL THEN
        RAISE EXCEPTION 'optional TableGroup venue rollout requires tbl_table_group';
    END IF;

    IF NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tbl_table_group'
           AND column_name = 'venue_id'
    ) OR NOT EXISTS (
        SELECT 1
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = 'tbl_table_group'
           AND column_name = 'venue_name'
    ) THEN
        RAISE EXCEPTION
            'optional TableGroup venue rollout requires venue_id and venue_name columns';
    END IF;
END
$$;

ALTER TABLE tbl_table_group
    ALTER COLUMN venue_id DROP NOT NULL,
    ALTER COLUMN venue_name DROP NOT NULL;

ALTER TABLE tbl_table_group
    DROP CONSTRAINT IF EXISTS ck_table_group_venue_shape;

ALTER TABLE tbl_table_group
    ADD CONSTRAINT ck_table_group_venue_shape
    CHECK (
        (
            venue_id IS NOT NULL
            AND (
                venue_name IS NULL
                OR char_length(btrim(venue_name, E' \t\n\r\f\013')) BETWEEN 1 AND 64
            )
        )
        OR
        (
            venue_id IS NULL
            AND (
                venue_name IS NULL
                OR char_length(btrim(venue_name, E' \t\n\r\f\013')) BETWEEN 1 AND 64
            )
        )
    ) NOT VALID;

ALTER TABLE tbl_table_group
    VALIDATE CONSTRAINT ck_table_group_venue_shape;

COMMENT ON COLUMN tbl_table_group.venue_id IS
    'Optional registered venue link; mutually exclusive with a custom venue name at the API boundary.';
COMMENT ON COLUMN tbl_table_group.venue_name IS
    'Optional custom venue name or registered-venue display snapshot; NULL when no venue is specified.';

COMMIT;
