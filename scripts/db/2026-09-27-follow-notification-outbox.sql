-- Historical person/band follow occurrences, one stable event per recipient.
-- Apply before the new backend; rerunnable, no backfill, no push capability marker.
-- Deliberately no FK to the ephemeral follow, band or account: deletion must not
-- cascade accepted work. Account erasure explicitly removes actor/recipient intents;
-- other missing/erased sources become SUPPRESSED at publication, never PUBLISHED.
BEGIN;
CREATE TABLE IF NOT EXISTS tbl_follow_notification_outbox (
    event_id uuid PRIMARY KEY,
    occurrence_id uuid NOT NULL,
    follower_id uuid NOT NULL,
    recipient_id uuid NOT NULL,
    band_id uuid,
    notification_type varchar(64) NOT NULL,
    occurred_at timestamptz NOT NULL,
    status varchar(24) NOT NULL,
    attempt_count integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL,
    lease_owner varchar(100),
    lease_until timestamptz,
    last_error_type varchar(200),
    published_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

-- Refuse a partial/incompatible pre-existing table rather than report success.
DO $shape$
DECLARE col record;
BEGIN
    FOR col IN SELECT * FROM (VALUES
        ('event_id','uuid',true), ('occurrence_id','uuid',true),
        ('follower_id','uuid',true), ('recipient_id','uuid',true), ('band_id','uuid',false),
        ('notification_type','character varying(64)',true), ('occurred_at','timestamp with time zone',true),
        ('status','character varying(24)',true), ('attempt_count','integer',true),
        ('next_attempt_at','timestamp with time zone',true), ('lease_owner','character varying(100)',false),
        ('lease_until','timestamp with time zone',false), ('last_error_type','character varying(200)',false),
        ('published_at','timestamp with time zone',false), ('created_at','timestamp with time zone',true),
        ('updated_at','timestamp with time zone',true)
    ) AS expected(name,typ,required) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='tbl_follow_notification_outbox'::regclass
            AND attname=col.name AND NOT attisdropped AND replace(format_type(atttypid,atttypmod),'(6)','')=col.typ
            AND attnotnull=col.required) THEN
            RAISE EXCEPTION 'Incompatible follow outbox column: %', col.name;
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='tbl_follow_notification_outbox'::regclass
        AND contype='p' AND pg_get_constraintdef(oid)='PRIMARY KEY (event_id)') THEN
        RAISE EXCEPTION 'Follow outbox requires event_id primary key';
    END IF;
END $shape$;

-- Reinstall exact checks atomically, including on Hibernate-shaped tables.
-- Invalid old data aborts the whole transaction, preserving its previous schema/data.
ALTER TABLE tbl_follow_notification_outbox
    DROP CONSTRAINT IF EXISTS ck_follow_outbox_status,
    DROP CONSTRAINT IF EXISTS ck_follow_outbox_attempts,
    DROP CONSTRAINT IF EXISTS ck_follow_outbox_type,
    DROP CONSTRAINT IF EXISTS ck_follow_outbox_lease,
    DROP CONSTRAINT IF EXISTS ck_follow_outbox_published,
    DROP CONSTRAINT IF EXISTS ck_follow_outbox_error,
    DROP CONSTRAINT IF EXISTS ck_follow_outbox_not_self;
ALTER TABLE tbl_follow_notification_outbox
    ADD CONSTRAINT ck_follow_outbox_status CHECK (status IN ('PENDING','IN_FLIGHT','PUBLISHED','DEAD_LETTER','SUPPRESSED')),
    ADD CONSTRAINT ck_follow_outbox_attempts CHECK (attempt_count BETWEEN 0 AND 100),
    ADD CONSTRAINT ck_follow_outbox_type CHECK (
        (notification_type='SOCIAL_NEW_FOLLOWER' AND band_id IS NULL)
        OR (notification_type='SOCIAL_NEW_BAND_FOLLOWER' AND band_id IS NOT NULL)),
    ADD CONSTRAINT ck_follow_outbox_lease CHECK (
        (status='IN_FLIGHT' AND lease_owner IS NOT NULL AND char_length(btrim(lease_owner)) BETWEEN 1 AND 100 AND lease_until IS NOT NULL)
        OR (status<>'IN_FLIGHT' AND lease_owner IS NULL AND lease_until IS NULL)),
    ADD CONSTRAINT ck_follow_outbox_published CHECK (
        (status='PUBLISHED' AND published_at IS NOT NULL) OR (status<>'PUBLISHED' AND published_at IS NULL)),
    ADD CONSTRAINT ck_follow_outbox_error CHECK (
        (last_error_type IS NULL OR last_error_type ~ '^[A-Za-z0-9_.$-]{1,200}$')
        AND (status NOT IN ('DEAD_LETTER','SUPPRESSED') OR last_error_type IS NOT NULL)),
    ADD CONSTRAINT ck_follow_outbox_not_self CHECK (follower_id<>recipient_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_follow_outbox_occurrence_recipient
    ON tbl_follow_notification_outbox (occurrence_id,recipient_id,notification_type);
CREATE INDEX IF NOT EXISTS idx_follow_outbox_due ON tbl_follow_notification_outbox (status,next_attempt_at,created_at);
CREATE INDEX IF NOT EXISTS idx_follow_outbox_lease ON tbl_follow_notification_outbox (status,lease_until);
CREATE INDEX IF NOT EXISTS idx_follow_outbox_published ON tbl_follow_notification_outbox (status,published_at);
CREATE INDEX IF NOT EXISTS idx_follow_outbox_actor ON tbl_follow_notification_outbox (follower_id);
CREATE INDEX IF NOT EXISTS idx_follow_outbox_recipient ON tbl_follow_notification_outbox (recipient_id);

DO $indexes$
DECLARE expected record;
BEGIN
    FOR expected IN SELECT * FROM (VALUES
        ('uk_follow_outbox_occurrence_recipient','occurrence_id,recipient_id,notification_type',true),
        ('idx_follow_outbox_due','status,next_attempt_at,created_at',false),
        ('idx_follow_outbox_lease','status,lease_until',false),
        ('idx_follow_outbox_published','status,published_at',false),
        ('idx_follow_outbox_actor','follower_id',false),
        ('idx_follow_outbox_recipient','recipient_id',false)
    ) AS shape(name,columns,is_unique) LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid
            WHERE i.indrelid='tbl_follow_notification_outbox'::regclass AND c.relname=expected.name
            AND i.indisvalid AND i.indisready AND i.indisunique=expected.is_unique
            AND i.indpred IS NULL AND i.indexprs IS NULL
            AND (SELECT string_agg(a.attname,',' ORDER BY k.ord)
                FROM unnest(i.indkey) WITH ORDINALITY AS k(num,ord)
                JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.num)=expected.columns) THEN
            RAISE EXCEPTION 'Incompatible follow outbox index: %',expected.name;
        END IF;
    END LOOP;
END $indexes$;

-- An intent cannot appear after erasure cleanup committed. This lock lives in
-- the follow transaction; publisher identity uses the same account-before-visibility order.
CREATE OR REPLACE FUNCTION soundconnect_follow_intent_accounts() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE account record; found_count integer := 0;
BEGIN
    FOR account IN SELECT id,erased_at FROM tbl_user WHERE id IN (NEW.follower_id,NEW.recipient_id) ORDER BY id FOR SHARE LOOP
        found_count := found_count+1;
        IF account.erased_at IS NOT NULL THEN
            RAISE EXCEPTION 'Follow notification account unavailable' USING ERRCODE='23514';
        END IF;
    END LOOP;
    IF found_count<>2 THEN
        RAISE EXCEPTION 'Follow notification account unavailable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS tr_follow_intent_accounts ON tbl_follow_notification_outbox;
CREATE TRIGGER tr_follow_intent_accounts BEFORE INSERT OR UPDATE OF follower_id,recipient_id
    ON tbl_follow_notification_outbox FOR EACH ROW EXECUTE FUNCTION soundconnect_follow_intent_accounts();
COMMIT;
