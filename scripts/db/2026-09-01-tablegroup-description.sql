-- Adds the optional-at-rest TableGroup description used by new create requests.
--
-- The new mobile client requires a normalized 1..280 character description,
-- while this compatibility backend still accepts missing/blank descriptions
-- from the released client and persists them as NULL. Existing rows also remain
-- NULL because no truthful legacy value can be inferred.
-- Run with psql -v ON_ERROR_STOP=1.

BEGIN;

DO $$
DECLARE
    description_type text;
BEGIN
    IF to_regclass('tbl_table_group') IS NULL THEN
        RAISE EXCEPTION 'table-group description rollout requires tbl_table_group';
    END IF;

    SELECT columns.data_type
      INTO description_type
      FROM information_schema.columns columns
     WHERE columns.table_schema = current_schema()
       AND columns.table_name = 'tbl_table_group'
       AND columns.column_name = 'description';

    IF description_type IS NOT NULL
       AND description_type NOT IN ('character varying', 'text') THEN
        RAISE EXCEPTION
            'table-group description rollout requires description to be varchar/text, found %',
            description_type;
    END IF;


    IF description_type IS NOT NULL THEN
        IF EXISTS (
            SELECT 1
              FROM tbl_table_group
             WHERE description IS NOT NULL
               AND (
                   description <> btrim(
                       description,
                       chr(9) || chr(10) || chr(11) || chr(12) || chr(13)
                       || chr(28) || chr(29) || chr(30) || chr(31) || chr(32)
                       || chr(160) || chr(5760)
                       || chr(8192) || chr(8193) || chr(8194) || chr(8195)
                       || chr(8196) || chr(8197) || chr(8198) || chr(8199)
                       || chr(8200) || chr(8201) || chr(8202)
                       || chr(8232) || chr(8233) || chr(8239)
                       || chr(8287) || chr(12288)
                   )
                   OR char_length(description) NOT BETWEEN 1 AND 280
               )
        ) THEN
            RAISE EXCEPTION
                'table-group description rollout found non-null descriptions that require manual normalization';
        END IF;
    END IF;
END
$$;

ALTER TABLE tbl_table_group
    ADD COLUMN IF NOT EXISTS description varchar(280);

-- A prior partial rollout may have used text or a different varchar bound.
-- The preflight above proves this conversion cannot truncate existing values.
ALTER TABLE tbl_table_group
    ALTER COLUMN description TYPE varchar(280)
    USING description::varchar(280);

ALTER TABLE tbl_table_group
    DROP CONSTRAINT IF EXISTS ck_table_group_description;

ALTER TABLE tbl_table_group
    ADD CONSTRAINT ck_table_group_description
    CHECK (
        description IS NULL
        OR (
            description = btrim(
                description,
                chr(9) || chr(10) || chr(11) || chr(12) || chr(13)
                || chr(28) || chr(29) || chr(30) || chr(31) || chr(32)
                || chr(160) || chr(5760)
                || chr(8192) || chr(8193) || chr(8194) || chr(8195)
                || chr(8196) || chr(8197) || chr(8198) || chr(8199)
                || chr(8200) || chr(8201) || chr(8202)
                || chr(8232) || chr(8233) || chr(8239)
                || chr(8287) || chr(12288)
            )
            AND char_length(description) BETWEEN 1 AND 280
        )
    ) NOT VALID;

ALTER TABLE tbl_table_group
    VALIDATE CONSTRAINT ck_table_group_description;

COMMENT ON COLUMN tbl_table_group.description IS
    'Required by the new client; NULL supports legacy rows and pre-description clients during rollout.';

COMMIT;
