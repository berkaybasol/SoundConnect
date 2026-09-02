-- SoundConnect TableGroup production-hardening rollout.
--
-- Run with: psql -v ON_ERROR_STOP=1 -f scripts/db/2026-08-17-tablegroup-hardening.sql
-- The migration is transactional and rerunnable. It can provision the module
-- on an already-baselined SoundConnect database, but it intentionally refuses
-- ambiguous participant duplicates or missing user identifiers instead of
-- silently discarding business data.

BEGIN;

CREATE TABLE IF NOT EXISTS tbl_table_group (
    id uuid NOT NULL,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    owner_id uuid NOT NULL,
    create_request_key uuid,
    venue_name varchar(128),
    venue_id uuid,
    max_person_count integer NOT NULL,
    age_min integer NOT NULL,
    age_max integer NOT NULL,
    start_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    status varchar(16) NOT NULL,
    city_id uuid NOT NULL,
    district_id uuid,
    neighborhood_id uuid,
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT pk_table_group PRIMARY KEY (id),
    CONSTRAINT fk_table_group_city FOREIGN KEY (city_id) REFERENCES tbl_city (id) ON DELETE RESTRICT,
    CONSTRAINT fk_table_group_district FOREIGN KEY (district_id) REFERENCES tbl_district (id) ON DELETE RESTRICT,
    CONSTRAINT fk_table_group_neighborhood FOREIGN KEY (neighborhood_id) REFERENCES tbl_neighborhood (id) ON DELETE RESTRICT
);

CREATE TABLE IF NOT EXISTS tbl_table_group_gender_prefs (
    table_group_id uuid NOT NULL,
    gender_pref varchar(16) NOT NULL,
    CONSTRAINT fk_table_group_gender_group
        FOREIGN KEY (table_group_id) REFERENCES tbl_table_group (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS tbl_table_group_participants (
    table_group_id uuid NOT NULL,
    user_id uuid NOT NULL,
    joined_at timestamp with time zone NOT NULL,
    status varchar(16) NOT NULL,
    join_note varchar(256),
    CONSTRAINT fk_table_group_participant_group
        FOREIGN KEY (table_group_id) REFERENCES tbl_table_group (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS tbl_table_group_message (
    id uuid NOT NULL,
    created_at timestamp without time zone,
    updated_at timestamp without time zone,
    table_group_id uuid NOT NULL,
    sender_id uuid NOT NULL,
    content text NOT NULL,
    message_type varchar(32) NOT NULL,
    deleted_at timestamp without time zone,
    CONSTRAINT pk_table_group_message PRIMARY KEY (id),
    CONSTRAINT fk_table_group_message_group
        FOREIGN KEY (table_group_id) REFERENCES tbl_table_group (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS tbl_table_group_notification_outbox (
    event_id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    notification_type varchar(64) NOT NULL,
    title varchar(160) NOT NULL,
    message varchar(1000) NOT NULL,
    payload jsonb NOT NULL,
    email_force boolean NOT NULL DEFAULT false,
    occurred_at timestamp with time zone NOT NULL,
    status varchar(24) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamp with time zone NOT NULL,
    lease_owner varchar(100),
    lease_until timestamp with time zone,
    last_error_type varchar(200),
    published_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT pk_table_group_notification_outbox PRIMARY KEY (event_id),
    CONSTRAINT ck_tg_notification_outbox_status
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER')),
    CONSTRAINT ck_tg_notification_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_tg_notification_outbox_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_tg_notification_outbox_title CHECK (char_length(btrim(title)) BETWEEN 1 AND 160),
    CONSTRAINT ck_tg_notification_outbox_message CHECK (char_length(btrim(message)) BETWEEN 1 AND 1000),
    CONSTRAINT ck_tg_notification_outbox_lease CHECK (
        (status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL)
        OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL)
    ),
    CONSTRAINT ck_tg_notification_outbox_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    ),
    CONSTRAINT ck_tg_notification_outbox_error_type CHECK (
        last_error_type IS NULL OR char_length(btrim(last_error_type)) BETWEEN 1 AND 200
    )
);

DO $table_group_outbox_constraints$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'tbl_table_group_notification_outbox'::regclass AND conname = 'ck_tg_notification_outbox_status') THEN
        ALTER TABLE tbl_table_group_notification_outbox ADD CONSTRAINT ck_tg_notification_outbox_status CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'DEAD_LETTER'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'tbl_table_group_notification_outbox'::regclass AND conname = 'ck_tg_notification_outbox_attempt_count') THEN
        ALTER TABLE tbl_table_group_notification_outbox ADD CONSTRAINT ck_tg_notification_outbox_attempt_count CHECK (attempt_count >= 0);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'tbl_table_group_notification_outbox'::regclass AND conname = 'ck_tg_notification_outbox_payload') THEN
        ALTER TABLE tbl_table_group_notification_outbox ADD CONSTRAINT ck_tg_notification_outbox_payload CHECK (jsonb_typeof(payload) = 'object');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'tbl_table_group_notification_outbox'::regclass AND conname = 'ck_tg_notification_outbox_title') THEN
        ALTER TABLE tbl_table_group_notification_outbox ADD CONSTRAINT ck_tg_notification_outbox_title CHECK (char_length(btrim(title)) BETWEEN 1 AND 160);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'tbl_table_group_notification_outbox'::regclass AND conname = 'ck_tg_notification_outbox_message') THEN
        ALTER TABLE tbl_table_group_notification_outbox ADD CONSTRAINT ck_tg_notification_outbox_message CHECK (char_length(btrim(message)) BETWEEN 1 AND 1000);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'tbl_table_group_notification_outbox'::regclass AND conname = 'ck_tg_notification_outbox_lease') THEN
        ALTER TABLE tbl_table_group_notification_outbox ADD CONSTRAINT ck_tg_notification_outbox_lease CHECK ((status = 'IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status <> 'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'tbl_table_group_notification_outbox'::regclass AND conname = 'ck_tg_notification_outbox_published') THEN
        ALTER TABLE tbl_table_group_notification_outbox ADD CONSTRAINT ck_tg_notification_outbox_published CHECK ((status = 'PUBLISHED' AND published_at IS NOT NULL) OR (status <> 'PUBLISHED' AND published_at IS NULL));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'tbl_table_group_notification_outbox'::regclass AND conname = 'ck_tg_notification_outbox_error_type') THEN
        ALTER TABLE tbl_table_group_notification_outbox ADD CONSTRAINT ck_tg_notification_outbox_error_type CHECK (last_error_type IS NULL OR char_length(btrim(last_error_type)) BETWEEN 1 AND 200);
    END IF;
END
$table_group_outbox_constraints$;

ALTER TABLE tbl_table_group
    ADD COLUMN IF NOT EXISTS version bigint;
ALTER TABLE tbl_table_group
    ADD COLUMN IF NOT EXISTS create_request_key uuid;
UPDATE tbl_table_group SET version = 0 WHERE version IS NULL;
ALTER TABLE tbl_table_group ALTER COLUMN version SET DEFAULT 0;
ALTER TABLE tbl_table_group ALTER COLUMN version SET NOT NULL;

-- Legacy Flutter clients sent expiresAt as an Istanbul wall clock without an
-- offset. Server-generated LocalDateTime values (start/join timestamps) were
-- produced by the production JVM configured with UTC. Preserve each intended
-- moment while moving to the unambiguous Instant/timestamptz contract.
-- Environments that already use timestamptz are left untouched.
DO $table_group_instant_rollout$
DECLARE
    expires_type text;
    start_type text;
    joined_type text;
BEGIN
    SELECT data_type INTO expires_type
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'tbl_table_group'
       AND column_name = 'expires_at';
    IF expires_type = 'timestamp without time zone' THEN
        ALTER TABLE tbl_table_group
            ALTER COLUMN expires_at TYPE timestamp with time zone
            USING expires_at AT TIME ZONE 'Europe/Istanbul';
    END IF;

    SELECT data_type INTO start_type
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'tbl_table_group'
       AND column_name = 'start_at';
    IF start_type = 'timestamp without time zone' THEN
        ALTER TABLE tbl_table_group
            ALTER COLUMN start_at TYPE timestamp with time zone
            USING start_at AT TIME ZONE 'UTC';
    END IF;

    SELECT data_type INTO joined_type
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'tbl_table_group_participants'
       AND column_name = 'joined_at';
    IF joined_type = 'timestamp without time zone' THEN
        ALTER TABLE tbl_table_group_participants
            ALTER COLUMN joined_at TYPE timestamp with time zone
            USING joined_at AT TIME ZONE 'UTC';
    END IF;
END
$table_group_instant_rollout$;

-- Every table has a finite, unambiguous lifetime. Legacy start_at was written
-- in the UTC-configured JVM; created_at is the only safe fallback. Refuse rows
-- with no source clock or a lifetime the hardened service would never create.
DO $table_group_lifetime_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM tbl_table_group
         WHERE start_at IS NULL AND created_at IS NULL
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: rows without start_at/created_at require manual reconciliation'
            USING ERRCODE = '23514';
    END IF;
END
$table_group_lifetime_preflight$;

UPDATE tbl_table_group
   SET start_at = created_at AT TIME ZONE 'UTC'
 WHERE start_at IS NULL;

DO $table_group_lifetime_bounds$
BEGIN
    IF EXISTS (
        SELECT 1 FROM tbl_table_group
         WHERE expires_at <= start_at
            OR expires_at > start_at + INTERVAL '24 hours'
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: lifetime must be greater than zero and at most 24 hours'
            USING ERRCODE = '23514';
    END IF;
END
$table_group_lifetime_bounds$;

DO $table_group_participant_preflight$
BEGIN
    IF EXISTS (
        SELECT 1 FROM tbl_table_group
         WHERE owner_id IS NULL OR max_person_count IS NULL
            OR age_min IS NULL OR age_max IS NULL OR expires_at IS NULL
            OR status IS NULL OR city_id IS NULL
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: required group fields contain nulls'
            USING ERRCODE = '23502';
    END IF;
    IF EXISTS (
        SELECT 1 FROM tbl_table_group_participants
         WHERE table_group_id IS NULL OR user_id IS NULL
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: participant rows with null group/user identifiers require manual reconciliation'
            USING ERRCODE = '23502';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group_participants
         GROUP BY table_group_id, user_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: duplicate (table_group_id,user_id) participant rows require manual reconciliation'
            USING ERRCODE = '23505';
    END IF;
    IF EXISTS (
        SELECT 1 FROM tbl_table_group_gender_prefs
         WHERE table_group_id IS NULL OR gender_pref IS NULL
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: gender slots with null group/value require manual reconciliation'
            USING ERRCODE = '23502';
    END IF;
    IF EXISTS (
        SELECT 1 FROM tbl_table_group_message
         WHERE table_group_id IS NULL OR sender_id IS NULL
            OR content IS NULL OR message_type IS NULL
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: required chat message fields contain nulls'
            USING ERRCODE = '23502';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group_message
         WHERE deleted_at IS NULL
         GROUP BY table_group_id
        HAVING count(*) > 10000
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: live chat rows exceed the 10000-message aggregate cap'
            USING ERRCODE = '23514';
    END IF;
END
$table_group_participant_preflight$;

-- The owner always consumes one accepted capacity slot. Terminal owner states
-- are ambiguous and must be reconciled explicitly; safe missing/pending owner
-- rows can be repaired deterministically.
DO $table_group_owner_preflight$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group group_row
          JOIN tbl_table_group_participants participant
            ON participant.table_group_id = group_row.id
           AND participant.user_id = group_row.owner_id
         WHERE participant.status IN ('REJECTED', 'KICKED', 'LEFT')
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: owner has a terminal participant state'
            USING ERRCODE = '23514';
    END IF;
END
$table_group_owner_preflight$;

UPDATE tbl_table_group_participants participant
   SET status = 'ACCEPTED'
  FROM tbl_table_group group_row
 WHERE participant.table_group_id = group_row.id
   AND participant.user_id = group_row.owner_id
   AND (participant.status IS NULL OR participant.status = 'PENDING');

INSERT INTO tbl_table_group_participants (table_group_id, user_id, joined_at, status, join_note)
SELECT group_row.id,
       group_row.owner_id,
       COALESCE(group_row.start_at, group_row.created_at AT TIME ZONE 'UTC', CURRENT_TIMESTAMP),
       'ACCEPTED',
       NULL
  FROM tbl_table_group group_row
 WHERE NOT EXISTS (
       SELECT 1
         FROM tbl_table_group_participants participant
        WHERE participant.table_group_id = group_row.id
          AND participant.user_id = group_row.owner_id
 );

-- A missing non-owner historic status must never grant chat access. PENDING
-- is the conservative recoverable state; the owner can decide it later.
UPDATE tbl_table_group_participants
   SET status = 'PENDING'
 WHERE status IS NULL;

DO $table_group_pending_bound$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group_participants
         WHERE status = 'PENDING'
         GROUP BY table_group_id
        HAVING count(*) > 50
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: groups with more than 50 pending applicants require manual reconciliation'
            USING ERRCODE = '23514';
    END IF;
END
$table_group_pending_bound$;

-- Terminal rows are not part of the client contract. Keep only the most
-- recent bounded history so legacy groups cannot permanently amplify every
-- aggregate load and authorization check.
WITH terminal_ranked AS (
    SELECT ctid,
           row_number() OVER (
               PARTITION BY table_group_id
               ORDER BY joined_at DESC NULLS LAST, user_id
           ) AS terminal_rank
      FROM tbl_table_group_participants
     WHERE status IN ('REJECTED', 'KICKED', 'LEFT')
)
DELETE FROM tbl_table_group_participants participant
 USING terminal_ranked ranked
 WHERE participant.ctid = ranked.ctid
   AND ranked.terminal_rank > 100;

UPDATE tbl_table_group_participants participant
   SET joined_at = COALESCE(group_row.created_at AT TIME ZONE 'UTC', CURRENT_TIMESTAMP)
  FROM tbl_table_group group_row
 WHERE participant.table_group_id = group_row.id
   AND participant.joined_at IS NULL;

ALTER TABLE tbl_table_group
    ALTER COLUMN owner_id SET NOT NULL,
    ALTER COLUMN max_person_count SET NOT NULL,
    ALTER COLUMN age_min SET NOT NULL,
    ALTER COLUMN age_max SET NOT NULL,
    ALTER COLUMN start_at SET NOT NULL,
    ALTER COLUMN expires_at SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN city_id SET NOT NULL;

ALTER TABLE tbl_table_group_participants ALTER COLUMN table_group_id SET NOT NULL;
ALTER TABLE tbl_table_group_participants ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE tbl_table_group_participants ALTER COLUMN joined_at SET NOT NULL;
ALTER TABLE tbl_table_group_participants ALTER COLUMN status SET NOT NULL;

ALTER TABLE tbl_table_group_gender_prefs
    ALTER COLUMN table_group_id SET NOT NULL,
    ALTER COLUMN gender_pref SET NOT NULL;

ALTER TABLE tbl_table_group_message
    ALTER COLUMN table_group_id SET NOT NULL,
    ALTER COLUMN sender_id SET NOT NULL,
    ALTER COLUMN content SET NOT NULL,
    ALTER COLUMN message_type SET NOT NULL;

-- Hibernate-built legacy schemas used implicit/absent foreign-key names. Add
-- the required aggregate and location relationships by structure, then
-- validate every matching constraint before the hardened binary is deployed.
DO $table_group_foreign_keys$
DECLARE
    constraint_row record;
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group_participants participant
          LEFT JOIN tbl_table_group group_row ON group_row.id = participant.table_group_id
         WHERE group_row.id IS NULL
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: orphan participant rows require manual reconciliation'
            USING ERRCODE = '23503';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group_gender_prefs gender
          LEFT JOIN tbl_table_group group_row ON group_row.id = gender.table_group_id
         WHERE group_row.id IS NULL
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: orphan gender slots require manual reconciliation'
            USING ERRCODE = '23503';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group group_row
          LEFT JOIN tbl_city city ON city.id = group_row.city_id
          LEFT JOIN tbl_district district ON district.id = group_row.district_id
          LEFT JOIN tbl_neighborhood neighborhood ON neighborhood.id = group_row.neighborhood_id
         WHERE city.id IS NULL
            OR (group_row.district_id IS NOT NULL AND (
                district.id IS NULL OR district.city_id <> group_row.city_id
            ))
            OR (group_row.neighborhood_id IS NOT NULL AND (
                neighborhood.id IS NULL
                OR group_row.district_id IS NULL
                OR neighborhood.district_id <> group_row.district_id
            ))
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: orphan or inconsistent group location hierarchy requires manual reconciliation'
            USING ERRCODE = '23503';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE contype = 'f'
           AND conrelid = 'tbl_table_group_participants'::regclass
           AND confrelid = 'tbl_table_group'::regclass
           AND conkey = ARRAY[(SELECT attnum FROM pg_attribute
                                WHERE attrelid = 'tbl_table_group_participants'::regclass
                                  AND attname = 'table_group_id')]::smallint[]
    ) THEN
        ALTER TABLE tbl_table_group_participants
            ADD CONSTRAINT fk_table_group_participant_group
            FOREIGN KEY (table_group_id) REFERENCES tbl_table_group (id)
            ON DELETE CASCADE NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE contype = 'f'
           AND conrelid = 'tbl_table_group_gender_prefs'::regclass
           AND confrelid = 'tbl_table_group'::regclass
           AND conkey = ARRAY[(SELECT attnum FROM pg_attribute
                                WHERE attrelid = 'tbl_table_group_gender_prefs'::regclass
                                  AND attname = 'table_group_id')]::smallint[]
    ) THEN
        ALTER TABLE tbl_table_group_gender_prefs
            ADD CONSTRAINT fk_table_group_gender_group
            FOREIGN KEY (table_group_id) REFERENCES tbl_table_group (id)
            ON DELETE CASCADE NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE contype = 'f'
           AND conrelid = 'tbl_table_group'::regclass
           AND confrelid = 'tbl_city'::regclass
           AND conkey = ARRAY[(SELECT attnum FROM pg_attribute
                                WHERE attrelid = 'tbl_table_group'::regclass
                                  AND attname = 'city_id')]::smallint[]
    ) THEN
        ALTER TABLE tbl_table_group
            ADD CONSTRAINT fk_table_group_city
            FOREIGN KEY (city_id) REFERENCES tbl_city (id)
            ON DELETE RESTRICT NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE contype = 'f'
           AND conrelid = 'tbl_table_group'::regclass
           AND confrelid = 'tbl_district'::regclass
           AND conkey = ARRAY[(SELECT attnum FROM pg_attribute
                                WHERE attrelid = 'tbl_table_group'::regclass
                                  AND attname = 'district_id')]::smallint[]
    ) THEN
        ALTER TABLE tbl_table_group
            ADD CONSTRAINT fk_table_group_district
            FOREIGN KEY (district_id) REFERENCES tbl_district (id)
            ON DELETE RESTRICT NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE contype = 'f'
           AND conrelid = 'tbl_table_group'::regclass
           AND confrelid = 'tbl_neighborhood'::regclass
           AND conkey = ARRAY[(SELECT attnum FROM pg_attribute
                                WHERE attrelid = 'tbl_table_group'::regclass
                                  AND attname = 'neighborhood_id')]::smallint[]
    ) THEN
        ALTER TABLE tbl_table_group
            ADD CONSTRAINT fk_table_group_neighborhood
            FOREIGN KEY (neighborhood_id) REFERENCES tbl_neighborhood (id)
            ON DELETE RESTRICT NOT VALID;
    END IF;

    FOR constraint_row IN
        SELECT conrelid::regclass AS relation_name, conname
          FROM pg_constraint
         WHERE contype = 'f'
           AND NOT convalidated
           AND (
               (conrelid = 'tbl_table_group_participants'::regclass AND confrelid = 'tbl_table_group'::regclass)
               OR (conrelid = 'tbl_table_group_gender_prefs'::regclass AND confrelid = 'tbl_table_group'::regclass)
               OR (conrelid = 'tbl_table_group'::regclass AND confrelid IN (
                   'tbl_city'::regclass, 'tbl_district'::regclass, 'tbl_neighborhood'::regclass
               ))
           )
    LOOP
        EXECUTE format('ALTER TABLE %s VALIDATE CONSTRAINT %I',
                       constraint_row.relation_name, constraint_row.conname);
    END LOOP;
END
$table_group_foreign_keys$;

DO $table_group_business_invariants$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group group_row
          LEFT JOIN tbl_table_group_participants participant
            ON participant.table_group_id = group_row.id
           AND participant.user_id = group_row.owner_id
           AND participant.status = 'ACCEPTED'
         GROUP BY group_row.id
        HAVING count(participant.user_id) <> 1
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: every group must have exactly one accepted owner row'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group group_row
          JOIN tbl_table_group_participants participant
            ON participant.table_group_id = group_row.id
           AND participant.status = 'ACCEPTED'
         GROUP BY group_row.id, group_row.max_person_count
        HAVING count(*) > group_row.max_person_count
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: accepted participants exceed max_person_count'
            USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group group_row
          LEFT JOIN tbl_table_group_gender_prefs gender
            ON gender.table_group_id = group_row.id
         GROUP BY group_row.id, group_row.max_person_count
        HAVING count(gender.gender_pref) <> group_row.max_person_count
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: gender slot count differs from max_person_count'
            USING ERRCODE = '23514';
    END IF;
END
$table_group_business_invariants$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_table_group_participant_user
    ON tbl_table_group_participants (table_group_id, user_id);

-- Powers the authenticated "my active tables" inbox lookup without scanning
-- every public city feed. table_group_id is included for an index-only join.
CREATE INDEX IF NOT EXISTS idx_tg_participant_user_status_group
    ON tbl_table_group_participants (user_id, status, table_group_id);

UPDATE tbl_table_group_message message_row
   SET created_at = COALESCE(
           message_row.updated_at,
           group_row.start_at AT TIME ZONE 'UTC',
           CURRENT_TIMESTAMP AT TIME ZONE 'UTC'
       )
  FROM tbl_table_group group_row
 WHERE message_row.table_group_id = group_row.id
   AND message_row.created_at IS NULL;

ALTER TABLE tbl_table_group_message
    ALTER COLUMN created_at SET NOT NULL;

-- The legacy message entity stored table_group_id as a scalar UUID, so
-- Hibernate-created upgrade schemas never received this aggregate FK.
DO $table_group_message_fk$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM tbl_table_group_message message_row
          LEFT JOIN tbl_table_group group_row ON group_row.id = message_row.table_group_id
         WHERE group_row.id IS NULL
    ) THEN
        RAISE EXCEPTION 'table-group rollout blocked: orphan chat messages require manual reconciliation'
            USING ERRCODE = '23503';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_table_group_message'::regclass
           AND conname = 'fk_table_group_message_group'
    ) THEN
        ALTER TABLE tbl_table_group_message
            ADD CONSTRAINT fk_table_group_message_group
            FOREIGN KEY (table_group_id) REFERENCES tbl_table_group (id)
            ON DELETE CASCADE NOT VALID;
    END IF;
END
$table_group_message_fk$;

ALTER TABLE tbl_table_group_message
    VALIDATE CONSTRAINT fk_table_group_message_group;

CREATE INDEX IF NOT EXISTS idx_tablegroup_venueid
    ON tbl_table_group (venue_id);
CREATE INDEX IF NOT EXISTS idx_tablegroup_venue_name
    ON tbl_table_group (venue_name);
CREATE INDEX IF NOT EXISTS idx_tablegroup_expires_at
    ON tbl_table_group (expires_at);
CREATE INDEX IF NOT EXISTS idx_tablegroup_status
    ON tbl_table_group (status);
CREATE INDEX IF NOT EXISTS idx_tablegroup_owner_status_exp_id
    ON tbl_table_group (owner_id, status, expires_at, id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_table_group_owner_create_request
    ON tbl_table_group (owner_id, create_request_key)
    WHERE create_request_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_group_city_status_exp
    ON tbl_table_group (city_id, status, expires_at);
CREATE INDEX IF NOT EXISTS idx_group_city_district_status_exp
    ON tbl_table_group (city_id, district_id, status, expires_at);
CREATE INDEX IF NOT EXISTS idx_group_city_district_neighborhood_status_exp
    ON tbl_table_group (city_id, district_id, neighborhood_id, status, expires_at);
-- Use a new name because older Hibernate-created schemas already own
-- idx_tg_msg_group_created with the weaker (table_group_id, created_at) shape.
CREATE INDEX IF NOT EXISTS idx_tg_msg_group_created_desc_id
    ON tbl_table_group_message (table_group_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
DROP INDEX IF EXISTS idx_tg_msg_group_created;
CREATE INDEX IF NOT EXISTS idx_tg_msg_sender
    ON tbl_table_group_message (sender_id);
CREATE INDEX IF NOT EXISTS idx_tg_notification_outbox_due
    ON tbl_table_group_notification_outbox (status, next_attempt_at, created_at);
CREATE INDEX IF NOT EXISTS idx_tg_notification_outbox_lease
    ON tbl_table_group_notification_outbox (status, lease_until);
CREATE INDEX IF NOT EXISTS idx_tg_notification_outbox_created
    ON tbl_table_group_notification_outbox (created_at);
CREATE INDEX IF NOT EXISTS idx_tg_notification_outbox_published
    ON tbl_table_group_notification_outbox (status, published_at);

-- Durable outbox delivery is at-least-once. Fence the shared notification
-- consumer with the stable event id even when the Collab rollout was not
-- previously installed in this environment.
ALTER TABLE tbl_notification
    ADD COLUMN IF NOT EXISTS source_event_id uuid,
    ADD COLUMN IF NOT EXISTS occurred_at timestamp with time zone;

UPDATE tbl_notification
   SET occurred_at = COALESCE(
           occurred_at,
           created_at AT TIME ZONE 'UTC',
           CURRENT_TIMESTAMP
       )
 WHERE occurred_at IS NULL;

ALTER TABLE tbl_notification ALTER COLUMN occurred_at SET NOT NULL;

DO $table_group_notification_dedupe$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conrelid = to_regclass(format('%I.%I', current_schema(), 'tbl_notification'))
           AND conname = 'uk_notification_source_event_id'
    ) THEN
        EXECUTE format(
            'ALTER TABLE %I.%I ADD CONSTRAINT %I UNIQUE (source_event_id)',
            current_schema(),
            'tbl_notification',
            'uk_notification_source_event_id'
        );
    END IF;
END
$table_group_notification_dedupe$;

CREATE INDEX IF NOT EXISTS idx_notification_recipient_occurred
    ON tbl_notification (recipient_id, occurred_at DESC, id DESC);

-- Registered venues now keep an optional, immutable display-name snapshot so
-- a table still renders consistently if the venue profile is renamed later.
-- Older rows used NULL/blank as the registered-venue marker; retain NULL for
-- that legacy shape, but normalize blanks before installing the widened check.
-- Match the common whitespace characters recognized by Java String.isBlank,
-- including horizontal/vertical tabs and line-breaking controls; PostgreSQL's
-- one-argument btrim would otherwise remove ordinary spaces only.
-- Venue association is optional. Drop legacy column-level requirements before
-- normalizing registered-venue blanks or installing the canonical shape check.
ALTER TABLE tbl_table_group
    ALTER COLUMN venue_id DROP NOT NULL,
    ALTER COLUMN venue_name DROP NOT NULL;

UPDATE tbl_table_group
   SET venue_name = NULL
 WHERE venue_id IS NOT NULL
   AND venue_name IS NOT NULL
   AND btrim(venue_name, E' \t\n\r\f\013') = '';

-- Replace, rather than merely create, the named constraint on every rerun.
-- This upgrades databases that already installed the earlier definition which
-- rejected nonblank registered-venue snapshots.
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

DO $table_group_constraints$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_table_group'::regclass
           AND conname = 'ck_table_group_status'
    ) THEN
        ALTER TABLE tbl_table_group ADD CONSTRAINT ck_table_group_status
            CHECK (status IN ('ACTIVE', 'INACTIVE', 'CANCELLED')) NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_table_group'::regclass
           AND conname = 'ck_table_group_capacity'
    ) THEN
        ALTER TABLE tbl_table_group ADD CONSTRAINT ck_table_group_capacity
            CHECK (max_person_count BETWEEN 2 AND 6) NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_table_group'::regclass
           AND conname = 'ck_table_group_age_range'
    ) THEN
        ALTER TABLE tbl_table_group ADD CONSTRAINT ck_table_group_age_range
            CHECK (age_min BETWEEN 19 AND 99 AND age_max BETWEEN 19 AND 99 AND age_min <= age_max) NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_table_group'::regclass
           AND conname = 'ck_table_group_lifetime'
    ) THEN
        ALTER TABLE tbl_table_group ADD CONSTRAINT ck_table_group_lifetime
            CHECK (expires_at > start_at AND expires_at <= start_at + INTERVAL '24 hours') NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_table_group_participants'::regclass
           AND conname = 'ck_table_group_participant_status'
    ) THEN
        ALTER TABLE tbl_table_group_participants ADD CONSTRAINT ck_table_group_participant_status
            CHECK (status IN ('PENDING', 'ACCEPTED', 'REJECTED', 'KICKED', 'LEFT')) NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_table_group_gender_prefs'::regclass
           AND conname = 'ck_table_group_gender_pref'
    ) THEN
        ALTER TABLE tbl_table_group_gender_prefs ADD CONSTRAINT ck_table_group_gender_pref
            CHECK (gender_pref IN ('FEMALE', 'MALE', 'OTHER')) NOT VALID;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_table_group_message'::regclass
           AND conname = 'ck_table_group_message_type'
    ) THEN
        -- A later release adds server-owned GAME cards. Local/dev databases can
        -- already have that Hibernate-created column and durable GAME rows while
        -- still be missing the SQL check constraint. Keep this earlier migration
        -- rerunnable across that partial-upgrade state without weakening a truly
        -- pre-game schema; the game migration installs the full shape constraint.
        IF EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = current_schema()
               AND table_name = 'tbl_table_group_message'
               AND column_name = 'game_id'
        ) THEN
            ALTER TABLE tbl_table_group_message ADD CONSTRAINT ck_table_group_message_type
                CHECK (message_type IN ('TEXT', 'SYSTEM', 'IMAGE', 'GAME')) NOT VALID;
        ELSE
            ALTER TABLE tbl_table_group_message ADD CONSTRAINT ck_table_group_message_type
                CHECK (message_type IN ('TEXT', 'SYSTEM', 'IMAGE')) NOT VALID;
        END IF;
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conrelid = 'tbl_table_group_message'::regclass
           AND conname = 'ck_table_group_message_content'
    ) THEN
        ALTER TABLE tbl_table_group_message ADD CONSTRAINT ck_table_group_message_content
            CHECK (char_length(btrim(content)) BETWEEN 1 AND 1000) NOT VALID;
    END IF;
END
$table_group_constraints$;

ALTER TABLE tbl_table_group VALIDATE CONSTRAINT ck_table_group_status;
ALTER TABLE tbl_table_group VALIDATE CONSTRAINT ck_table_group_capacity;
ALTER TABLE tbl_table_group VALIDATE CONSTRAINT ck_table_group_age_range;
ALTER TABLE tbl_table_group VALIDATE CONSTRAINT ck_table_group_lifetime;
ALTER TABLE tbl_table_group VALIDATE CONSTRAINT ck_table_group_venue_shape;
ALTER TABLE tbl_table_group_participants VALIDATE CONSTRAINT ck_table_group_participant_status;
ALTER TABLE tbl_table_group_gender_prefs VALIDATE CONSTRAINT ck_table_group_gender_pref;
ALTER TABLE tbl_table_group_message VALIDATE CONSTRAINT ck_table_group_message_type;
ALTER TABLE tbl_table_group_message VALIDATE CONSTRAINT ck_table_group_message_content;

COMMIT;
