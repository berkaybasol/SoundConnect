-- Additive online indexes. Run in autocommit; CONCURRENTLY must not run inside a transaction.
-- No event, plan, comment, participation or history data is deleted.
-- Raw SQL time differs from the entity LocalTime when Hibernate's JDBC zone differs
-- from the JVM zone. Keep the index independent of that deployment setting. Recent-day
-- cutoff/order expressions normalize through the active mapping; older days use this range.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_event_owner_history_date
ON tbl_event (venue_id, event_date DESC, start_time DESC, id DESC)
WHERE event_origin = 'VENUE';
