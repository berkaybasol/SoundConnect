# Musician feed development performance check

The same isolated workload also supports the venue audience:
`./gradlew musicianFeedLoadTest -PfeedLoadAudience=VENUE`. Its report and plans
go to `build/reports/venue-feed-load/`; the report explicitly records audience.
The 32 venue viewers have real approved, owned venue/profile records and the
existing location chain. Local and national artist pools use the same artist
fixture, with 128 non-followed artists assigned the fixture city. Default
MUSICIAN fixtures and gates remain unchanged. See [venue policy](venue-feed-v1.md).

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
historical deliveries, 6,400 recent qualified impressions and 32,032 feedback
rows. The impression fixture was added for algorithm v1.2.0. Other provider families,
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

## Operational metrics

The existing Micrometer registry now records `soundconnect.musician.feed.*`:
`page.duration` (service-boundary success/error), `provider.execution` (actual
worker execution, including late completion), `provider.results` (request
outcome: success/failure/timeout/capacity/interrupted/cancelled), executor
`providers.active` and `providers.queued`, post-filter `candidates` by type/lane,
`response.items` by type, and `history.unavailable` for soft-history fallback.
Response item counters include replay and are not unique deliveries or qualified
impressions. Tags contain fixed provider/type/lane/outcome values, no account,
session or content identifiers. Existing actuator exposure/authorization is
unchanged; no new public monitoring endpoint is introduced.

Announcement read transactions now have a five-second SQL transaction timeout,
matching the established provider reads. The four-second provider deadline
still does not promise a four-second HTTP response or immediate JDBC cancellation.

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

The 13 September results below are historical. For the current algorithm see
the 14 September verification at the end of this document.

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

## Audit fixes verified — 2026-09-14

The updated source passed 445 relevant backend tests across 66 suites with no
failures or skips, including real PostgreSQL/Redis and the preceding auth fixes.
The broad run passed 442 tests, then the final mixer/service suites were rerun
with three additional pagination regressions; 445 counts each latest test once.

The separate load task also passed with the new 6,400-impression history fixture
and the existing limits unchanged. Service/JDBC p95 was 154.15 ms serial,
492.81 ms with 8 viewers, 1,009.94 ms with 16 viewers, and 1,098.38 ms for
same-cursor replay. All 573 providers completed without warning/failure and all
16 concurrent replay responses were byte-identical. This workload differs from
the previous fixture; the numbers are not a controlled speed comparison.

Evidence is retained under `../.local-verification/feed-audit-20260914/`, including
the fresh XML archives, final suite summary, load log and `backend-load-report/`.
The local Windows host retained the user's running application; no test restarted
it or applied fixture data to the application database. PostgreSQL 16.4-alpine
was disposable. Test builds use the repository's established local main-JAR
classpath workaround in an isolated build directory. CPU, Docker limits and
source hashes are retained in the verification manifest beside these reports.

## Listener / Mainstage verification — 2026-09-14

The same explicit task now accepts `-PfeedLoadAudience=LISTENER`. Listener mode
uses real listener profile/account-city repositories and current media audience
checks; it does not read musician preferences. Its fixture retains 50,000 tracks,
10,000 future events, 6,400 qualified views and the existing worker/queue/DB limits.
Music discovery remains national; listener cities provide event relevance.

The listener run passed: serial / 8-viewer / 16-viewer / same-cursor p95 was
152.07 / 542.38 / 931.22 / 1,103.22 ms. All 573 provider invocations completed,
there were no provider failures or service warnings, and all 16 simultaneous
replays were byte-identical. TRACK/EVENT/PROFILE service/JDBC coverage and the
HTTP/media/production limitations above still apply. No application data was
used or changed. No other Flutter/Gradle tests or analyzers ran during the timed
workload. This is not a controlled comparison with earlier audiences.

Functional verification of this change combines 306 suites: 2,042 tests passed,
zero failures/errors, and two default-disabled native FFmpeg test declarations.
The disabled parameterized declaration represents two cases when explicitly
enabled. Listener PostgreSQL/provider/privacy tests executed without skips.
Latest suite XML replaces overlapping earlier suite runs in this count.

Evidence is under `../.local-verification/listener-feed-20260914/`:
`backend-final-summary.json`, `backend-final-xml/`, `backend-listener-load.log`,
`listener-load-report/`, `listener-load-xml/`, `load-host.json` and
`source-hashes.json`. The Windows host exposed 16 logical CPUs and Docker had
16 CPUs / 16,737,001,472 bytes of memory; PostgreSQL was disposable 16.4-alpine.
