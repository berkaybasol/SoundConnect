-- TableGroup TEXT-message response-loss replay support.
-- Nullable-at-rest keeps legacy clients and server-owned GAME/SYSTEM rows compatible.

BEGIN;

DO $table_group_chat_idempotency_preflight$
DECLARE
    base_column_count integer;
BEGIN
    IF to_regclass('tbl_table_group_message') IS NULL THEN
        RAISE EXCEPTION
            'TableGroup chat idempotency migration requires tbl_table_group_message';
    END IF;

    SELECT count(*)
      INTO base_column_count
      FROM pg_attribute
     WHERE attrelid = 'tbl_table_group_message'::regclass
       AND attname IN ('table_group_id', 'sender_id')
       AND atttypid = 'uuid'::regtype
       AND NOT attisdropped;

    IF base_column_count <> 2 THEN
        RAISE EXCEPTION
            'TableGroup chat idempotency migration requires uuid table_group_id and sender_id columns';
    END IF;
END
$table_group_chat_idempotency_preflight$;

ALTER TABLE tbl_table_group_message
    ADD COLUMN IF NOT EXISTS client_message_id uuid;

DO $table_group_chat_idempotency_column$
DECLARE
    column_type oid;
    column_not_null boolean;
BEGIN
    SELECT atttypid, attnotnull
      INTO column_type, column_not_null
      FROM pg_attribute
     WHERE attrelid = 'tbl_table_group_message'::regclass
       AND attname = 'client_message_id'
       AND NOT attisdropped;

    IF column_type IS DISTINCT FROM 'uuid'::regtype OR column_not_null THEN
        RAISE EXCEPTION
            'TableGroup chat idempotency migration requires nullable uuid client_message_id';
    END IF;
END
$table_group_chat_idempotency_column$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_tg_msg_client_message_id
    ON tbl_table_group_message (table_group_id, sender_id, client_message_id)
    WHERE client_message_id IS NOT NULL;

DO $table_group_chat_idempotency_index$
DECLARE
    index_is_unique boolean;
    index_table oid;
    index_columns text[];
    index_predicate text;
BEGIN
    SELECT index_meta.indisunique,
           index_meta.indrelid,
           ARRAY(
               SELECT attribute.attname
                 FROM unnest(index_meta.indkey) WITH ORDINALITY AS key_column(attnum, position)
                 JOIN pg_attribute attribute
                   ON attribute.attrelid = index_meta.indrelid
                  AND attribute.attnum = key_column.attnum
                ORDER BY key_column.position
           ),
           regexp_replace(
               lower(pg_get_expr(index_meta.indpred, index_meta.indrelid)),
               '[()[:space:]]',
               '',
               'g'
           )
      INTO index_is_unique, index_table, index_columns, index_predicate
      FROM pg_index index_meta
     WHERE index_meta.indexrelid = to_regclass('uk_tg_msg_client_message_id');

    IF index_table IS DISTINCT FROM 'tbl_table_group_message'::regclass
       OR index_is_unique IS DISTINCT FROM true
       OR index_columns IS DISTINCT FROM ARRAY[
            'table_group_id', 'sender_id', 'client_message_id'
       ]::text[]
       OR index_predicate IS DISTINCT FROM 'client_message_idisnotnull' THEN
        RAISE EXCEPTION
            'uk_tg_msg_client_message_id exists with an incompatible definition';
    END IF;
END
$table_group_chat_idempotency_index$;

COMMIT;
