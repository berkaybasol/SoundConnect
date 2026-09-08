# Venue analytics: bounded reporting load rehearsal

## Purpose and safety

Run `./gradlew.bat venueAnalyticsLoadTest` from the backend repository with Java 21
and Docker available. This is an opt-in verification task. The ordinary `test`
task excludes the `analytics-load` tag so a unit/security run does not silently
become a load run.

The task starts its own non-reusable PostgreSQL 16.4 Testcontainer, verifies the
exact JDBC URL and `venue_analytics_load_test` database name before writing, and
closes the datasource and container afterward. It never imports application
configuration, `.env.local`, real authentication tokens, or application data.
Only synthetic fixtures are used. No production/developer database is reset.
The analytics migration is applied solely to this disposable database.

## Workload

- 10,001 synthetic events and 120,000 retained daily facts spread over 90 days.
- 2,000 expired facts to exercise concurrent bounded cleanup.
- Eight concurrent workers sharing a maximum of eight database connections.
- 350 operations per worker, 2,800 measured operations total.
- Current 7/30/90-day summaries, daily trends and eligible complete-period comparisons.
- Server-side metric ordering, first/middle/last pages and ordinary date pages.
- Individual event reports for 90 days.
- Ingestion of 400 fresh three-observation batches from distinct synthetic
  installations, each followed by an identical retry while reporting is running.
- Ordinary event-list SQL sharing the same connection pool.

All operations must succeed. Final assertions verify exact counts for the hot
event and venue, unique receipt counts despite repeated retries, unchanged
completed-period comparisons after today's writes, and removal of expired
facts. No safety/rate-limit settings of the actual application are changed.

The report is written to `build/reports/venue-analytics-load/report.json`.
It includes sample counts, p50/p95/p99 for each operation, elapsed time and
operation throughput. The serial warm baseline contains only three samples per
read type and is diagnostic, not a statistically established latency baseline.
Most concurrent operation types have 400 samples each; the 400 summary reads are
split across 7/30/90-day windows. A five-second p95 guard catches a broad
local regression against the store transaction budget; it is not the target
latency for the shipped product.

## Interpretation limits

This is a closed-workload PostgreSQL/store test: each worker waits for its
operation to complete before starting the next. It is not a sustained external
arrival-rate test. The small synthetic event schema intentionally includes only
columns used by analytics and does not reproduce every application trigger,
index, table, background job or workload.

The result does **not** establish HTTP/API throughput, JWT/Redis quota behavior,
mobile frame performance, long-offline delivery, a full-app soak result, or a
production concurrency/SLA guarantee. HTTP/security/Redis regressions have
separate tests. The local machine also hosts the load generator and database,
which differs from a deployment with separate services.

Before launch, repeat a representative mixed HTTP workload in a production-like
isolated environment, including discovery, profile reads, observation batches,
all report windows and cleanup. Use independent virtual identities and account
for IP/global quotas: a fast 429 rejection is not successful analytics capacity.
Measure accepted observations/second, p95/p99, unexpected errors, database/pool
waits and resources, and cleanup backlog. Normal, burst and sustained scenarios
need explicit pass/fail criteria and safe stop thresholds. That broader release
exercise remains separate from this focused venue reporting rehearsal.

## Recorded local run — September 8, 2026

`./gradlew.bat venueAnalyticsLoadTest --console=plain --no-daemon` passed: one
opt-in test, zero failures/errors/skips. The measured mixed phase took 17.246
seconds for 2,800 successful operations, with 400 fresh batches and 400 exact
retries. All count, attribution, completed-period and cleanup assertions passed.

| Database operation | Samples | Concurrent p95 |
|---|---:|---:|
| 7-day summary, daily series and comparison | 133 | 79.46 ms |
| 30-day summary, daily series and comparison | 134 | 334.95 ms |
| 90-day summary and daily series | 133 | 243.78 ms |
| Server-sorted metric page | 400 | 144.07 ms |
| Ordinary event lookup sharing the pool | 400 | 1.37 ms |
| Fresh three-observation batch plus identical retry | 400 | 30.14 ms |

The test's temporary containers were independently checked absent afterward.
The initial sandboxed attempt could not access Docker's named pipe; the actual
recorded run used approved Docker access. These values describe this short,
synthetic local rehearsal only, under the limitations above. They are not user
counts, network latency targets, or permission to increase production quotas.
