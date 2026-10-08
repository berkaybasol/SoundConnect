-- Online index for keyset pages of an individual post's likers.
-- Run with autocommit: CREATE INDEX CONCURRENTLY must not run in a transaction.
SET lock_timeout = '5s';
SET statement_timeout = '30min';
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_like_target_created
    ON public.tbl_like(target_type,target_id,created_at DESC,id DESC);

CREATE TABLE IF NOT EXISTS public.soundconnect_schema_migrations (
    migration_id varchar(160) PRIMARY KEY,
    applied_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO public.soundconnect_schema_migrations(migration_id)
VALUES ('2026-09-12-like-users-pagination') ON CONFLICT DO NOTHING;
RESET statement_timeout;
RESET lock_timeout;
