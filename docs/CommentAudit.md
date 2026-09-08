# Comment flow audit — 2026-09-08

Scope: actual `EVENT`, `MEDIA` and `OVERTHINKING` comment roots/replies, authenticated creation/deletion, viewer-dependent author identity and pagination. Existing target likes were inspected only: there is no `COMMENT` like target and this change does not introduce comment likes or modify target-like mutation semantics. Venue analytics reporting remains disabled and untouched.

## Preserved API and policies

- Generic authenticated roots: `GET /api/v1/comments/{targetType}/{targetId}`. Replies: `GET /api/v1/comments/replies/{rootCommentId}`. Both already return `BaseResponse<Page<...>>`; clients must retain `content`, `number`, `size`, `totalElements`, `totalPages` and `last`, not flatten them into an unbounded list.
- Public event roots/replies retain the dedicated endpoints in [PublicEventComments.md](PublicEventComments.md). Both HTTP routes default to size 20 (maximum 50); the generic service's internal null-pageable fallback is 10. Page numbers above 1000 are rejected. Server sort is fixed: roots `createdAt DESC, id DESC`; replies `createdAt ASC, id ASC`.
- Creation remains authenticated `POST /api/v1/comments/{targetType}/{targetId}` with `{text,parentCommentId?}`. Text is trimmed, nonblank, and limited to the existing HTTP `@Size(max=500)` contract (Java UTF-16 units, not a new codepoint-length promise). Exactly one reply level is allowed. The target must be readable; replies must match the root's type and target ID.
- Delete remains `DELETE /api/v1/comments/{commentId}`, owner-only and idempotent. There is no admin override. Owners may delete after a target is hidden/deleted. Soft-deleted rows retain their identity and original text in storage; responses substitute `[Bu yorum silinmiştir]`. Existing replies remain readable on a soft-deleted root when its target is still visible; no new replies may be added.
- Authentication remains the existing JWT active/verified-account and listener-choice policy. Generic reads remain authenticated; only event-specific GETs are guest-readable. Existing security cache headers include `no-store` for viewer-dependent identities.

## Correctness/privacy fixes

Generic reads and comment creation can no longer bypass target visibility. Event targets use the existing public comment eligibility (venue-origin, venue-calendar publication, approved venue, active/verified venue owner; known past events remain readable). Media must be `PUBLIC`, `READY` and renderable; ghost/pending listener-owned or legacy USER-owned profile media follows existing hidden profile-media policy. Missing overthinking posts return not-found, and missing anonymous-post context also masks author identity defensively.

Target checks use fresh scalar shared locks held through the operation, including listener visibility before media access. Reads run in repeatable-read transactions so page rows/counts and author masking share a snapshot. These read transactions are deliberately not database `readOnly`, because PostgreSQL disallows shared row locks there. They do not mutate data.

Reply creation and delete take the same fresh scalar comment lock. Parent deletion, cross-target/depth checks and insertion cannot race using stale entity state. Native target reads also fence concurrent target deletion/visibility edits. No new table, migration or application data conversion is required.

Author payloads preserve `{id,username,avatarUrl,visibilityMode?}`: the canonical username remains the name contract. Anonymous post authors are masked from other viewers (null ID/avatar); their own authenticated view retains identity for owner deletion. Ghost listeners retain only their canonical handle/listener avatar and `GHOST` marker; pending-choice listeners get the existing `Kullanici`/null-avatar compatibility projection. Ghost/pending checks are fresh scalar locked projections; alternate personal/business profiles cannot override them.

For standard authors, the page batches musician, listener, organizer, producer, studio and venue avatar candidates and resolves only public/READY media. Repeated authors are resolved once, without hydrating per-comment user/profile graphs. A configured but non-displayable profile image never falls back to stale legacy identity. Legacy UUIDs still pass through public/READY resolution; legacy HTTP(S) URLs are accepted only when no profile avatar is configured. Venue-owner fallback deterministically selects the first configured venue profile by venue creation time/UUID; the API does not add a new acting-as-venue identity selector.

## Verification and remaining boundaries

Tests use explicit fresh, non-reused Testcontainers PostgreSQL datasources and verify URL/database identity before fixture writes. Coverage includes hidden targets through generic routes, former anonymous posts, ghost/pending media, owner deletion, deleted roots, concurrent reply/deletion, equal-timestamp root/reply last pages, all six profile avatar sources, ghost/pending precedence and 50 distinct listener authors with bounded SQL/no user-profile hydration. HTTP regressions cover authenticated actor propagation, page metadata, guest restrictions, private cache headers and text validation. Adjacent authentication, media cleanup and anonymous-profile tests are included in the final run recorded below.

This is not a production load/capacity certification. Offset pages can shift between separate requests when other users insert comments; deterministic ordering is not a cross-request snapshot or cursor API. POST creation is **not idempotent**: a timed-out response may still have committed. Clients must not automatically retry, must prevent duplicate taps, and must disclose uncertain delivery before a user decides to resubmit. Adding durable command IDs would require a separate schema/API decision. No new moderation/admin, edit, block or retention policy was invented; soft-deleted text retention remains unchanged. The subsequently approved content-scoped burst guard and its bounded limitations are documented in [CommentBurstProtection.md](CommentBurstProtection.md). A global 10/minute quota and per-comment cooldown were not selected.

No real database, migration, local environment, backend process or Docker infrastructure was changed. Source changes require an explicitly coordinated backend deployment/restart before affecting the running application.

Final bounded verification:

```powershell
.\gradlew.bat --no-daemon test --tests '*Comment*Test' --tests '*SecurityConfigAuthorizationTest' --tests '*JwtAuthenticationFilterSecurityTest' --tests '*ListenerProfileChoiceGateTest' --tests '*MediaAssetServiceImplTest' --tests '*MediaEngagementCleanup*Test' --tests '*EngagementTargetValidator*Test' --tests '*Overthinking*Test'
```

Passed: **116 tests across 17 suites, 0 failures, 0 errors, 0 skipped** (1m40s). Comment coverage is 67 tests: 8 new isolated PostgreSQL service tests, 9 service unit tests, 7 batched-identity unit tests, 19 HTTP/security tests, 12 public adapter tests, 10 existing isolated PostgreSQL adapter tests, and 2 mapper ghost/anonymity tests. The other 49 are adjacent auth/media/anonymity regressions. The 50-author performance assertion uses real PostgreSQL rows without configured avatar media: at most 7 SQL statements, exactly 50 comment entities and no user/profile/collection hydration; configured avatar enrichment is a separate bounded media batch, not a network/storage integration benchmark.

## Approved same-content burst protection follow-up

The 116-test result above records the preceding comment audit, not the subsequently added guard. [CommentBurstProtection.md](CommentBurstProtection.md) records the approved behavior and availability limits.

`CommentBurstGuard` is invoked inside the comment-create transaction after input/access/parent validation and before persistence. Rollback cleanup is registered before reserving in Redis, because a lost Redis response can hide an executed reservation. A confirmed 429 inserted no token, so its rollback does not issue an extra cleanup command. Cleanup for a definitely rolled-back write removes only its unique token, never the entire user's content counter. Committed and uncertain outcomes keep their reservations.

`CommentBurstGuardRedisTest` executes the real production Lua in a disposable Redis container. It covers the first three sends, fourth rejection, nonextending rejection TTL, user/content/type isolation, rolling expiry, concurrent requests through two separately configured Redis clients, rollback-token isolation, Redis command failure and a lost reservation response after actual Lua execution. Time-boundary tests age only isolated fixture scores instead of sleeping for 30 seconds. They are not a load benchmark.

`CommentBurstGuardTest` covers missing/write-transaction requirements, invalid results, safe errors and best-effort rollback behavior. Service tests verify that invalid input/access/parents never reserve and that rejected sends do not save. A real isolated PostgreSQL transaction test flushes an insert then rolls back, verifying that the guard releases its reservation. Its Redis operations are a controlled fake, while Redis script behavior is tested separately against the real disposable Redis. HTTP tests cover `9356`/429 and `9357`/503 with `Retry-After` and private cache headers for roots/replies across EVENT, MEDIA and OVERTHINKING.

Final follow-up verification used the same Gradle command recorded above: **143 tests across 19 suites, 0 failures, 0 errors, 0 skipped**, 2m53s. XML files in `build/test-results/test` were aggregated to verify those counts. Comment coverage is 94 tests (9 Redis guard, 8 unit guard, 25 HTTP/security, 12 service unit, 9 PostgreSQL service, 10 PostgreSQL public adapter, 12 public adapter service, 2 mapper and 7 author batch) plus the same 49 adjacent regressions. These replace, rather than add to, the earlier run's totals. The generated local HTML report is `build/reports/tests/test/index.html`.

## Device-discovered timestamp correction

The user reported a just-created comment appearing three hours old on a Turkey-timezone phone. The UTC `LocalDateTime` produced by the actual `JpaAuditingConfig` was serialized without an offset in both comment response DTOs. A client parsing that string as local time interpreted a UTC wall clock as UTC+03 wall clock. This case was missed by the earlier audit.

`CommentResponseDto` and `CommentReplyResponseDto` now expose `Instant createdAt` with explicit string/UTC JSON formatting. All mapper paths convert the existing UTC database wall-clock value using `ZoneOffset.UTC`. Public event roots/replies, generic roots/replies and creation responses therefore preserve the instant with an explicit `Z`. Null timestamps remain null. The change does not rewrite historical rows, assume a different storage timezone, change ordering, alter event scheduling times or modify global time configuration. Records written before the current UTC storage contract are not heuristically rewritten.

The pre-fix serialization regression failed three timezone variants with the missing `Z`. `CommentTimestampContractTest` now verifies actual Jackson behavior under UTC, Istanbul and Los Angeles configurations. HTTP tests cover the public and generic root/reply/create contracts. Isolated PostgreSQL service tests now import the real UTC auditing provider and verify fresh root/reply instants against the request interval, UTC midnight boundaries, unchanged stored wall-clock values and timestamp preservation after soft deletion.

Final combined timestamp verification:

```powershell
.\gradlew.bat --no-daemon test --tests '*Comment*Test' --tests '*JpaAuditingConfigTest' --tests '*SecurityConfigAuthorizationTest' --tests '*JwtAuthenticationFilterSecurityTest' --tests '*ListenerProfileChoiceGateTest' --tests '*MediaAssetServiceImplTest' --tests '*MediaEngagementCleanup*Test' --tests '*EngagementTargetValidator*Test' --tests '*Overthinking*Test'
```

**153 tests across 21 suites passed, 0 failures, 0 errors, 0 skipped**, 2m15s. XML totals were independently checked: 103 comment tests, 1 UTC auditor test and 49 adjacent regressions. This is one combined run, not a sum of repeated executions. No real application database, running backend, environment or migration was changed.

## Expandable replies / 400-record pagination verification — 2026-09-09

The frontend reply UI changed to compact, opt-in expandable threads. A new isolated `CommentServicePostgresTest` fixture verifies 400 roots and 400 replies written by 400 distinct listener authors. Generic authenticated reads, public guest reads and public authenticated reads are exercised at page sizes 20, 37 and 50. Every page preserves the explicit timestamp/UUID order, exact totals, deleted placeholders, target isolation and terminal/empty-page behavior. Page bounds and cross-event/nested-parent rejection remain covered.

The maximum measured SQL counts per page are generic roots/replies 6/6 and public roots/replies 7/8. Hydration is limited to the requested comment page, plus one parent for replies. No user/profile/collection graphs are hydrated. The fixture does not benchmark configured avatar storage/network access. Existing target/parent indexes were inspected in source, not the live application database. Separate offset requests remain subject to concurrent insertion shifts and do not promise a cross-request snapshot.

```powershell
.\gradlew.bat --no-daemon test --tests '*CommentServicePostgresTest' --tests '*CommentServiceImplTest' --tests '*EventCommentRead*Test'
```

**75 tests across 5 suites passed, 0 failures, 0 errors, 0 skipped**, 1m36s. This narrower result is a separate run, not added to the preceding 153-test audit. Only test/docs changed in this backend follow-up. No production API, schema, application database, running backend or environment was modified. These are functional/query-bound checks, not production load or capacity certification.

## COMMENT likes and calendar-day follow-up — 9 September 2026

The latest follow-up extends the existing like infrastructure to comments/replies, rather than inventing a parallel like table. `POST /api/v1/likes/COMMENT/{id}` sets liked, `DELETE` sets unliked and `GET .../state` reconciles uncertain delivery. COMMENT mutation/state payloads are `{likeCount,likedByMe}`; other target mutation payloads remain unchanged. Root/reply/create DTOs carry both fields. Guests receive public counts with `likedByMe=false`.

Desired-state writes use native `ON CONFLICT DO NOTHING` under existing user/type/target uniqueness. A fresh active/verified actor, actual content visibility and the comment's exact target/root relationship are checked before mutation. The same comment lock used by deletion serializes competing writes. Missing/deleted/hidden/misbound comments fail closed. A soft-deleted root's still-visible existing replies remain likeable. COMMENT is not a valid commentable content target. No liker list, notification or identity disclosure was added. Media hard cleanup also removes subordinate comment likes.

Paged counts and viewer state use one combined bounded aggregation, not one request per row. The 400-root/400-reply fixture with likes verifies maximum SQL statements per root/reply page: generic7/7, public8/9 at20/37/50, without user/profile graph hydration. Offset pagination's documented cross-request limitations remain unchanged.

Calendar scope: venue weekly selection now uses the existing Istanbul `EventScheduleClock`; all frontend profile calendars expire prior-day entries on midnight/resume. Today's ended event remains through its day. Venue profiles no longer replace an empty authoritative week with historical events. History/publication preferences/listener posts remain unchanged.

Final combined run: **219 tests across28 suites passed,0 failures/errors/skips**,2m58s. Breakdown: comment/like126, migration order1, UTC auditor1, adjacent49, calendar42. Includes five isolated migration rehearsals. These are functional/privacy/concurrency/query-bound checks, not production load certification.

```powershell
.\gradlew.bat --no-daemon test --tests '*Comment*Test' --tests '*LikeServiceImplTest' --tests '*JpaAuditingConfigTest' --tests '*SecurityConfigAuthorizationTest' --tests '*JwtAuthenticationFilterSecurityTest' --tests '*ListenerProfileChoiceGateTest' --tests '*MediaAssetServiceImplTest' --tests '*MediaEngagementCleanup*Test' --tests '*EngagementTargetValidator*Test' --tests '*Overthinking*Test' --tests '*VenueWeeklyCalendarDayTest' --tests '*VenueWeeklyEventPosterTest' --tests '*MusicianCalendarServiceTest' --tests '*BandCalendarServiceTest' --tests '*EventLocalSchemaMigrationOrderTest'
```

`scripts/db/2026-09-09-comment-likes.sql` widens only the like enum CHECK, preserves existing data and other checks, and ensures the target index. It validates the required immediate unique constraint used by atomic upsert and is registered after event audience in `scripts/dev.ps1`. The user stopped the backend and authorized backup/application in this turn. The local migration was applied after restored-copy rehearsal. See [local migration record](CommentLikesLocalMigration20260909.md). The backend was left stopped until the user started current source.

## Device-reported missing selected hearts — identity integration regression

9 September 2026. The user reported correct counts but unselected hearts after reopening an event. The frontend used public event comment URLs, on which its transport deliberately omitted the Bearer header even when a session fence was supplied. The backend correctly returned guest counts with `likedByMe=false`. Prior separate repository mocks and injected-principal HTTP tests had not exercised this transport gap.

Frontend correction uses existing authenticated generic comment routes for eligible signed-in accounts, retaining public adapters for guests/ineligible viewers. No backend production code, schema or running service changed in this follow-up. Generic reads support active verified listener, musician, venue, studio, organizer, producer and admin roles without a special artist/listener restriction. Their root/reply DTOs, content privacy checks and pagination are shared. Generic replies are scoped by root ID, not an additional event ID; frontend current-event/root/session checks and reply-parent validation remain in place. Invalid authentication cannot silently downgrade these generic endpoints to guests.

New `EventCommentJwtViewerPropagationTest` exercises a real signed JWT, provider/util/filter, listener gate, security chain and controllers. User loading and comment storage boundaries are mocked; it is not a real-database latency test. Fifteen cases cover repeated reads, omitted-header reproduction, account/guest switches, expired/malformed JWT, current account eligibility, pending profile choice and seven active role variants. Existing PostgreSQL tests separately cover real stored like projections and bounded page queries.

```powershell
.\gradlew.bat --no-daemon test --tests '*EventCommentJwtViewerPropagationTest' --tests '*EventCommentReadSecurityTest' --tests '*JwtAuthenticationFilterSecurityTest'
```

**58 tests / 3 suites passed, zero failures/errors/skips, 55 seconds.** This separate focused run is not added to the earlier overlapping 219-test count. Phone re-entry is pending user retest after Flutter hot restart.
