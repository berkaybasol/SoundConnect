# Musician feed development performance check

Run `./gradlew musicianFeedLoadTest` with Java 21 and Docker available. The task
is excluded from ordinary `test`; it runs only when requested explicitly. The
fixture exists in a fresh Testcontainers PostgreSQL 16.4 instance, not in the
application database. The test does not import application configuration or
local credentials.

Results and exact `EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON)` plans are
written to `build/reports/musician-feed-load/`. Archive that directory before
another run. `report.json` records setup completion, status, provider warnings,
latency and correctness evidence even when acceptance fails. A setup failure
or `diagnostic-only` status is not a passed load test.

For plan inspection without the timed concurrency phases, use
`./gradlew musicianFeedLoadTest -PfeedLoadDiagnosticsOnly=true`.

## What it measures

The real production service/JDBC boundary, TRACK/EVENT/PROFILE providers,
viewer guards, feedback/moderation filters, mixer, delivery, continuation and
replay run against the real PostgreSQL schema. The synthetic fixture includes
50,000 tracks, 10,000 future events, 100,000 likes, 50,000 comments, 32,000
historical deliveries and 32,032 feedback rows. Other provider families,
HTTP/authentication transport, Redis budgets, preference storage, external
media delivery and production infrastructure are outside this measurement.

The fixed development gates are p95 <= 1 second for serial/8-viewer phases and
p95 <= 2 seconds for 16-viewer/same-cursor phases. Page completeness, unique
item/target identities, positions, signed continuation/delivery data, persistent
mute filtering and concurrent replay must remain correct. Provider warnings
or incomplete provider calls cannot be counted as a successful fast response.

The executor has 6 workers and a 24-entry queue, the provider deadline is
4 seconds, the JDBC pool has 10 connections and a page has 20 items. These
limits must not be enlarged merely to make a failing run pass.

Production admission waits only within that same absolute provider deadline
when the bounded queue is full. A job that cannot be admitted or cannot begin
before the deadline returns the existing API error envelope with code 1324 and
HTTP 503. It must not persist an empty page or continuation replay. Queued work
is removed on cancellation; an already running provider still occupies its
worker until it exits. This does not claim that interrupting a future instantly
stops JDBC. Execution failure after an optional provider has actually started
retains the established optional-provider policy.

This is a closed workload: each viewer awaits a response before requesting its
next page. It is a bounded development regression check, not an arrival-rate
test, public latency promise, maximum capacity claim or prolonged soak test.
The fixture does not establish performance for every data distribution or a
large history of event member publications. Those require further scenarios
when their target volumes are known.

Run timed phases without other builds or load generators. Record host/CPU,
Docker resource limits, PostgreSQL version and the tested source revision.
Inspect plans when a gate fails; do not treat partial/empty provider output as
a latency improvement. Keep functional PostgreSQL regressions alongside any
query optimization, including suppression, all publisher identities, ordering,
and enough filtered leading items to catch an incorrect early candidate cap.

The separate real-device Flutter render benchmark and its isolation boundaries
are documented in the frontend `integration_test/README.md`. Its controlled
repository latency is not a backend measurement.

## Session closure — 2026-09-13

The full backend regression suite passed: 4,094 tests passed, zero failures or
errors, and one preexisting disabled `SoundConnectApplicationTests.contextLoads`
test. PostgreSQL/Redis integrations executed, including all three real-process
announcement FFmpeg tests. This total excludes the separate load task below.

A fresh isolated `musicianFeedLoadTest` also passed with the production limits
above unchanged. On the local Windows development host, p95 was 112.81 ms serial,
285.31 ms with 8 viewers, 589.22 ms with 16 viewers, and 660.21 ms for same-cursor
replay. All 573 provider calls completed without warnings/errors, and all 16
concurrent replays were byte-identical. This remains the TRACK/EVENT/PROFILE
service/JDBC workload described above; it is not an announcement placement load
test, an HTTP/media measurement, or a production capacity claim.

Machine details, tested source digest, logs, fresh XML summary, load report and
query plans are retained locally under
`../.local-verification/session-close-20260913/backend/`. Generated reports and
application data are not committed. The cross-repository handoff is in the
frontend repository at `docs/session-handoff-20260913.md`.
