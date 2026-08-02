-- SoundConnect username canonicalization rollout.
--
-- Prerequisites:
--   * Reconcile this script against an authoritative production schema dump.
--   * Resolve every reported canonical collision manually.
--   * Stop application writes and run psql with ON_ERROR_STOP=1.
--
-- This project intentionally has no automatic migration runner. The script is
-- transactional and rerunnable, following the existing manual release gate.

BEGIN;

LOCK TABLE tbl_user IN ACCESS EXCLUSIVE MODE;

DO $migration$
DECLARE
	root_collation_count bigint;
BEGIN
	SELECT count(*)
	INTO root_collation_count
	FROM pg_collation
	WHERE collname = 'und-x-icu'
	  AND collprovider = 'i'
	  AND collisdeterministic;

	IF root_collation_count = 0 THEN
		RAISE EXCEPTION
			'username canonicalization requires deterministic ICU root collation und-x-icu'
			USING ERRCODE = '0A000';
	END IF;
END
$migration$;

-- Java Character.toLowerCase(int) and Dart String.toLowerCase() both use a
-- simple, per-code-point mapping for the supported corpus. Processing one
-- Unicode character at a time avoids contextual final-sigma mapping. U+0130
-- is explicit because ICU otherwise expands it to i + combining dot.
CREATE OR REPLACE FUNCTION public.soundconnect_canonical_username(input text)
RETURNS text
LANGUAGE sql
IMMUTABLE
STRICT
PARALLEL SAFE
SET search_path = pg_catalog
AS $canonical$
	SELECT coalesce(
		string_agg(
			CASE
				WHEN character = U&'\0130' THEN 'i'
				ELSE lower(character COLLATE pg_catalog."und-x-icu")
			END,
			'' ORDER BY ordinal
		),
		''
	)
	FROM regexp_split_to_table(
		btrim(
			input,
			U&'\0009\000A\000B\000C\000D\0020\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000\FEFF'
		),
		''
	) WITH ORDINALITY AS characters(character, ordinal);
$canonical$;

DO $migration$
DECLARE
	invalid_username_count bigint;
	collision_group_count bigint;
BEGIN
	SELECT count(*)
	INTO invalid_username_count
	FROM tbl_user
	WHERE user_name IS NULL
	   OR public.soundconnect_canonical_username(user_name) = '';

	IF invalid_username_count > 0 THEN
		RAISE EXCEPTION
			'username canonicalization blocked: % null or blank usernames require manual repair',
			invalid_username_count
			USING ERRCODE = '23514',
			      CONSTRAINT = 'ck_tbl_user_username_canonical';
	END IF;

	SELECT count(*)
	INTO collision_group_count
	FROM (
		SELECT public.soundconnect_canonical_username(user_name) COLLATE "C"
		FROM tbl_user
		GROUP BY public.soundconnect_canonical_username(user_name) COLLATE "C"
		HAVING count(*) > 1
	) canonical_collisions;

	IF collision_group_count > 0 THEN
		RAISE EXCEPTION
			'username canonicalization blocked: % collision groups require manual resolution',
			collision_group_count
			USING ERRCODE = '23505',
			      CONSTRAINT = 'ux_tbl_user_username_canonical';
	END IF;
END
$migration$;

UPDATE tbl_user
SET user_name = public.soundconnect_canonical_username(user_name)
WHERE user_name COLLATE "C" IS DISTINCT FROM
      public.soundconnect_canonical_username(user_name) COLLATE "C";

ALTER TABLE tbl_user
	DROP CONSTRAINT IF EXISTS ck_tbl_user_username_canonical;

ALTER TABLE tbl_user
	ADD CONSTRAINT ck_tbl_user_username_canonical
	CHECK (
		user_name IS NOT NULL
		AND user_name <> ''
		AND user_name COLLATE "C" =
		    public.soundconnect_canonical_username(user_name) COLLATE "C"
	);

DROP INDEX IF EXISTS ux_tbl_user_username_canonical;

CREATE UNIQUE INDEX ux_tbl_user_username_canonical
	ON tbl_user (((public.soundconnect_canonical_username(user_name)) COLLATE "C"));

COMMIT;
