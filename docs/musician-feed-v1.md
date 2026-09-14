# Musician Feed v1

The approved venue adaptation reuses this engine with artist discovery and no
profile completion. Its differences are documented in [Venue feed v1](venue-feed-v1.md).

This document is the product and engineering source of truth for the first
Backstage feed shown to a musician account. Changes to the decisions below
require an explicit product decision; implementation details may evolve while
preserving these contracts.

## Product promise

The musician feed is one strong, server-ranked Backstage stream where a
musician follows their professional/social circle, discovers actionable
opportunities, and encounters relevant music content. It is not restricted to
musician authors. Eligible authors are musicians, bands, approved venues,
studios, and standard listeners. Organizer and producer profile types are out
of scope. Ghost listeners cannot publish and must never produce feed items.

There is no Following/Discover split and no feed composer in the first
production release. Existing top and bottom navigation remain unchanged.

## Eligible item families

- A followed account's public Track. Adding the Track to the public profile is
  the publication act; there is no second feed-share step.
- A followed account's public ProfileMedia gallery/video attachment. Attaching
  it to the public profile is the publication act. Avatars and other profile
  decoration are not publications.
- An OPEN, unexpired Collab listing. Publishing the listing is the publication
  act; Collab has no separate profile-share wrapper.
- An eligible future Event, including explicit performer-profile publication,
  a followed standard listener's Event profile post, and low-rate discovery.
- A followed standard listener's explicit Overthinking or TableGroup profile
  share. Raw/global Mainstage posts never enter Backstage. The card links back
  to its source module.
- Selected activity by followed accounts on Backstage-eligible targets:
  follow, like, and comment. Mainstage-only activity is excluded.
- Low-rate discovery from accounts the viewer does not follow.
- A conditional profile-completion carousel.
- Sponsored or editorial placements inserted by the sponsor mixer.

The viewer's own publications are excluded from their feed.

## Publication and engagement identity

Every item has a stable feed identity and a stable engagement target. These
must not be inferred from display text.

- Track and ProfileMedia engagement belongs to the underlying public
  MediaAsset target.
- Event engagement belongs to Event unless the item is a listener Event
  profile post, in which case engagement belongs to the Event-post wrapper.
- TableGroup profile-share engagement belongs to its share wrapper.
- Overthinking profile-share engagement belongs to its share wrapper. Existing
  source-post engagement remains untouched; profile-share UI must stop writing
  new reactions/comments to the source post.
- Collab keeps its native actions (save, apply, report, open/share) and does not
  gain synthetic likes/comments.

Removed likes/follows, deleted comments, inaccessible targets, closed/expired
listings, invalid events, deleted attachments, and restricted listener content
must not survive as feed candidates.

## Personalization

The first explicit personalization signals are:

1. opportunity city: "Which city do you want to see opportunities in?"
2. the musician profile's canonical instrument IDs

Genre is deliberately deferred. Opportunity city is not residence or a public
address. It is stored outside the location aggregate and references the
existing canonical City row. The Location and Collab core workflows remain
unchanged.

Missing preferences never block the feed. The mixer falls back to following,
freshness and exploration, filling a sparse primary stream with available
discovery. City/instrument matches increase opportunity relevance; source
weights, diversity and opportunity reservations also affect final order.
Relevance is normally a soft
score; visibility, status, authorization, and safety are hard filters.
Opportunity city is also a soft relevance signal for eligible future Events
and Venue/Studio profile discovery; it is never a visibility filter.

## Completion carousel

The carousel contains all incomplete, user-actionable tasks and orders them by
actual impact:

1. opportunity city
2. instruments
3. biography
4. portfolio (at least one public READY Track or profile-media publication)
5. profile photo and social links

Backend state is authoritative and versioned. Internally, public profile
completeness and feed-personalization readiness are separate concepts even if
the UI presents a simple completed-step count. Completion is a nudge, never an
authentication or route gate.

Completion criteria v2 emits `BIO` instead of the v1 `STAGE_NAME_AND_BIO`
machine code. The legacy code remains readable for short-lived delivery replay
compatibility, but new completion responses never require or emit a stage name.
The legacy musician-profile field remains outside the feed identity contract;
musician display identity in this feed is the account username.

## Ranking and mixing invariants

- Followed publications are the strongest social pool.
- A followed user's comment is a stronger reason than their like.
- Same-target likes/follows may aggregate into one reason row; comments remain
  individual. The strongest reason is primary and other activity is secondary
  social proof.
- A dense primary stream reserves roughly 10% for general discovery; unused
  primary capacity can be filled by discovery so new accounts do not receive
  two-card pages when useful candidates are available.
- Candidate providers are bounded. The feed mixer owns cross-source ranking,
  author/type diversity, deduplication, and cursor stability.
- Cards expose a machine-readable reason code; copy is rendered by the client.
- The viewer can hide an item with the close action. The overflow menu supports
  show-less, mute-author, and report. These actions are persisted and affect
  future ranking/eligibility.
- Discovery profile cards include Follow.

### Algorithm v1.2.0 — 14 September 2026

The user approved the musician-feed audit fixes before venue-feed work.
Diversity now affects selection from the full bounded candidate pool at each
slot. Reordering only an already truncated page would never admit an author
outside that page. The last five organic author/type identities from the
delivery ledger also influence the next page. These are soft penalties, so a
sparse pool is still usable.

When available, relevant Collab/Event opportunities receive a minimum of
`floor(normal selection slots / 5)` from five slots upward (four for twenty
normal slots). Venue/Studio profile suggestions do not satisfy this opportunity
reservation. Opportunities cannot all be displaced by later promotional
insertion. Completion remains at most one, module shares keep their budget and
separation rules, and a one-card request retains useful primary content.

Qualified organic impressions from the preceding 24 hours subtract 240,000
ranking points. This is neither permanent exclusion nor learned engagement
prediction. The query uses the fixed session anchor and excludes the current
session; prefetched cards are not views. A storage/transaction failure in this
soft enrichment retains valid content and increments an operational counter.
Provider limits stay unchanged: no claim is made that unseen content beyond a
provider's bounded candidate window will always reach the mixer.

See [seen history and announcement frequency](musician-feed-recent-views.md).
The algorithm version participates in cursor ranking context. Old-version
continuations use the existing refresh-required path after deployment.

On the client, returning from media or announcement details reconciles the
existing engagement target through the shared engagement repositories. It
preserves loaded pages, cursor and feed session, updates all target aliases,
and rejects outdated reads after account/content/local mutation changes.
Successful follow overlays expire after a subsequent authoritative first-page
read, allowing an unfollow from another screen to become visible.

## Feedback capacity and reads

Feedback has no lifetime 5,000-row ceiling. Existing HIDE, REPORT, MUTE_AUTHOR,
and SHOW_LESS preferences are retained; crossing the former ceiling neither
rejects a new preference nor evicts an old one. This change introduces no
preference expiry or automatic deletion. Report evidence and its associated
preference still commit or roll back together in the existing transaction;
delivery verification, capability checks, and idempotency remain enforced.

Feed reads do not load a viewer's full feedback history. The ranking projection
performs one indexed lookup per supported item type, each capped at ten
SHOW_LESS rows to match the existing mixer penalty saturation. The ranking
context hashes these effective counts; repeating a preference or adding an
eleventh SHOW_LESS for the same type does not change its ranking weight or
invalidate an otherwise compatible cursor.

After bounded candidate collection, a central reader checks HIDE, REPORT, and
MUTE_AUTHOR only for the collected organic and promotion identities. It
deduplicates exact `(viewer, action, scope_key)` probes and queries them in
batches of at most 500. Existing provider suppression stays before provider
LIMITs, and the central check also protects completion cards and future
providers. Replay uses exact scoped preference probes for its exposed items
and identities. These checks retain arbitrarily old matching preferences
without materializing unrelated history. They do not promise atomic visibility
with a preference committed after the last check.

Before deploying this reader, apply the additive, repeatable migration
`scripts/db/2026-09-13-musician-feed-feedback-lookup.sql` after the original
feedback migration. It adds `(viewer_user_id, action, item_type, id)` and
`(viewer_user_id, item_id, action)` indexes; no preference or moderation data is
modified. The script uses a transaction with bounded lock/statement timeouts;
schedule index creation for the database's table size and write traffic.
Durable history can still grow, so storage and database load need normal
operational monitoring; bounded projections are not a storage-retention policy.

The new ranking-context hash may invalidate an outstanding continuation once
on deployment. The existing `MUSICIAN_FEED_CURSOR_INVALID` refresh path handles
this; a still-eligible committed replay keeps its existing behavior.

## Muted authors

`GET /api/v1/feed/musician/muted-authors?limit=30&cursor=...` lists the
authenticated musician's persistent author mutes, newest first. The default
limit is 30 and the maximum is 50. The response data contains `items`, nullable
`nextCursor`, and `hasMore`; each item contains `profileType`, `profileId`,
nullable `displayName` and `avatarUrl`, `available`, and ISO-instant `mutedAt`.

Preferences are paged before their current public identities are resolved in
one bounded query. Deleted, inactive, Ghost, unapproved, or otherwise
ineligible identities remain in the list with `available=false` and null name
and avatar. The client renders a generic label and still allows unmute.
Configured private or unavailable avatar assets never fall back to an old
legacy avatar URL.

Pagination uses a signed, viewer-scoped cursor with a fixed list anchor and
the `(mutedAt, feedback UUID)` keyset, preserving continuation when the boundary
row is unmuted. Invalid or expired cursors return `MUSICIAN_FEED_CURSOR_INVALID`;
the client reloads the list. Responses use private, no-store cache controls.
The existing `DELETE /api/v1/feed/musician/authors/{profileType}/{profileId}/mute`
remains idempotent and accepts the normalized saved identity even when that
profile is no longer public or no longer exists.

## Report moderation

Generic feed reports have an administrator queue, immutable delivery evidence,
versioned decisions, and feed-only restrictions. The backend controller and
service both require the exact `MANAGE_MUSICIAN_FEED_REPORTS` authority. The
migration grants this permission to `ROLE_ADMIN` and `ROLE_OWNER`; the runtime
initializer also assigns it to these roles. A role name alone does not bypass
the permission check. Authorities are loaded from the current server-side user
and role permissions. After a deployment grants the permission, refresh the
client's authenticated session/permission payload (sign out and in if needed)
so a previously cached session exposes the new admin entry.

The Flutter route is `/admin/musician-feed/reports`, reachable through the
admin dashboard's "Akış şikâyetleri" entry. It includes status/type filters,
detail, decision notes, history, and an entry to orphaned restrictions. Route,
screen, dialog, and request checks bind access to the active account, token,
and permission; changing or revoking that session clears sensitive state and
invalidates pending responses. The admin endpoints and UI remain available
when the musician-feed rollout flag is disabled. They do not require a
musician profile.

All successful admin responses use the standard `BaseResponse.data` envelope
and `Cache-Control: no-store, private`. The contracts are:

- `GET /api/v1/admin/musician-feed/reports`: `status` defaults to `NEW`,
  `itemType` is optional, and `limit` defaults to 20 with a maximum of 50.
  `cursor` continues the list. Data is `{items, nextCursor, hasMore}`.
- Each summary contains `id`, `version`, `status`, `itemId`, `itemType`,
  `targetType`, `targetId`, nullable `reason`, `reportedAt`, `title`, and nullable
  `authorDisplayName`. Queue SQL projects only these fields; it does not load
  full evidence or history. Titles are bounded to 160 characters and author
  names to 255, including nested activity and profile-share source fields.
- `GET /api/v1/admin/musician-feed/reports/{reportId}` returns `{report,
  reporterUserId, evidence, scopeDescription, allowedDecisions,
  activeRestriction, history}`. `report` is the same summary, and
  `scopeDescription` may be null when the recorded target cannot be resolved
  safely. History entries contain `id`, `decision`, `previousStatus`, `status`,
  `actorUserId`, `occurredAt`, and `resolutionNote`.
- `POST /api/v1/admin/musician-feed/reports/{reportId}/review` accepts
  `{clientRequestId, expectedVersion, decision, resolutionNote}` and returns
  the detail contract. The request ID is a UUID, the expected version is
  nonnegative, and the stripped note must contain 5–500 UTF-16 code units.
  Invalid controls and unpaired surrogates are rejected; tab and newline are
  allowed. The actor always comes from the authenticated principal.

Queue pagination uses descending `(reportedAt, report UUID)` keysets and a
fixed anchor, so deleting or reviewing the boundary row does not require it
to remain present. Its opaque Base64url JSON cursor is versioned, at most
1,024 characters, bound to the viewer/status/type filter, and expires after
24 hours. The timestamp range is validated before SQL conversion. These admin
cursors are validated list positions, not signed authorization tokens; every
request independently checks moderation authority. Changing filters or using
an invalid/expired cursor requires a fresh list.

The server supplies the allowed decisions according to this state machine:

| Current status | Decision | Resulting status |
| --- | --- | --- |
| `NEW` | `START_REVIEW` | `REVIEWING` |
| `NEW` or `REVIEWING` | `DISMISS` | `DISMISSED` |
| `NEW` or `REVIEWING` | `REMOVE_FROM_FEED` | `ACTIONED` |
| `ACTIONED` | `RESTORE_TO_FEED` | `RESTORED` |

Removal additionally requires a supported, unambiguous scope; restoration
requires this report's own active restriction. Unsupported legacy evidence
can still be reviewed and dismissed. Each successful transition locks the
report row, increments its version, changes its restriction when applicable,
and appends an audit entry in one transaction. It does not lock sibling
reports. A failure rolls back all three writes.

The audit has a unique `(report_id, client_request_id)` key. Retrying the same
request ID with the same actor, expected version, decision, and normalized note
does not repeat the mutation or append another history entry; it returns the
current detail. Reusing that ID with changed input, submitting a stale version
under a new ID, or requesting a disallowed transition returns HTTP 409
`MUSICIAN_FEED_REPORT_CONFLICT` (1322). A missing report returns HTTP 404
`MUSICIAN_FEED_REPORT_NOT_FOUND` (1321); an invalid admin cursor returns HTTP
400 `MUSICIAN_FEED_REPORT_CURSOR_INVALID` (1323). Other malformed input returns
400; authentication/permission failures return 401/403. The UI preserves the
request ID for an unchanged uncertain retry, reloads on conflict, and clears
the detail when a report disappears.

### Restriction scope and enforcement

A removal changes visibility in the musician feed. It does not delete or edit
the source post, media, event, profile, or account, and it does not create an
account-wide ban. Scope keys use typed identities:

| Reported item | Persisted scope | Effect |
| --- | --- | --- |
| Track / ProfileMedia | `TARGET:MEDIA:{id}` | That media's feed publications and activities |
| Event | `TARGET:EVENT:{id}` | That event's feed presentations, including listener event posts |
| Event profile share | `TARGET:EVENT_POST:{id}` | That wrapper's feed presentations |
| Overthinking profile share | `TARGET:OVERTHINKING_PROFILE_SHARE:{id}` | That wrapper's feed presentations |
| TableGroup profile share | `TARGET:TABLE_GROUP_POST:{id}` | That wrapper's feed presentations |
| Profile | `TARGET:PROFILE:{profileType}:{id}` | That profile card and activities targeting it |
| Like/comment/follow activity | `ITEM:{itemId}` | Only the reported feed story, without suppressing its source content |
| Standalone sponsored creative | `ITEM:{itemId}` | Only that creative's feed story |

Each report owns one independent restriction with an immutable scope. Any
active restriction for a matching scope suppresses the presentation. Restoring
one report only deactivates that report's restriction; another report may keep
the content restricted. Accordingly, detail `activeRestriction` reflects the
scope's combined effect, while `allowedDecisions` determines whether this
particular report can be restored. A restored detail can legitimately have
`activeRestriction=true`.

Native provider SQL checks applicable restrictions before candidate-window
LIMITs, so removed candidates do not occupy all available slots. A central
guard also filters collected organic and promotion candidates. It resolves
typed presentation scopes and probes the restriction index in batches of at
most 500 scopes. Replay performs the same current-restriction check for its
bounded committed page. A newly restricted cached page returns
`MUSICIAN_FEED_CURSOR_INVALID`; the client refreshes instead of receiving the
restricted replay. An eligible page remains unchanged, including its original
tokens and engagement counters. As with current visibility and personal
feedback, a restriction committed after the last check is not an atomic
revocation of an already returned response. New candidate families must add
both appropriate provider predicates and central/replay scope support.

Collab reports continue through the existing Collab report/moderation
aggregate, including reports dispatched from feed deliveries. Generic feed
moderation does not remove or restore Collab listings. Profile completion does
not offer REPORT. Standalone creative scope support does not create a campaign
provider or authorize promoted replay; the sponsorship integration limitations
below still apply.

### Evidence and reporter erasure

The stored evidence envelope is `{itemId, itemType, author, target, reason,
payload}`, copied unchanged from the recorded public-safe delivery. It reflects
delivery time, without refreshing source content when a report is filed. Its `reason`
is the feed recommendation reason; the reporter's optional explanation is the
report's separate `reason` field. This snapshot is not a live source read and
does not recover identities hidden from the original viewer. If serialization
would exceed 32,768 bytes, `payload` becomes
`{"omitted":true,"reason":"MAX_EVIDENCE_BYTES"}` while metadata remains.
Reports stay readable after delivery cleanup, including omitted-payload and
legacy records.

The admin preview projects known snapshot text/image fields as read-only
content. It never executes evidence links, routes, HTML, or scripts. Activity
payloads contain the target preview, but `ACTIVITY_COMMENT` evidence does not
contain the comment text. The UI states that limitation; the original comment
cannot be assessed from this snapshot alone. Missing or omitted fields are not
silently replaced with newly fetched source content.

Existing reporter erasure remains unchanged: deleting the reporter cascades
to their report and its report audit. Restrictions deliberately have logical
report/actor UUIDs without cascading foreign keys, so erasure does not unhide
content for other viewers. A report `AFTER DELETE` trigger marks only the
restriction with `report_id=OLD.id` as `orphaned=true`, including a deletion
caused by the reporter foreign-key cascade. The restriction stays active.

`GET /api/v1/admin/musician-feed/restrictions` manages these remaining active
orphaned restrictions with the same authority and cache policy. It accepts
`limit` (default 20, maximum 50) and `cursor`; data is `{items, nextCursor,
hasMore}`. Items contain `reportId`, `scopeDescription`, `scopeKey`,
`appliedByUserId`, `appliedAt`, and `updatedAt`. No erased reporter identity or
evidence is reconstructed. Pagination uses descending `(appliedAt, reportId)`
with a viewer-bound, purpose-specific, 24-hour opaque cursor. The partial index
on `(active, applied_at DESC, report_id DESC) WHERE orphaned` bounds the queue
to orphaned restrictions; a defensive report-existence check remains in SQL.

`POST /api/v1/admin/musician-feed/restrictions/{reportId}/restore` accepts
`{clientRequestId, expectedUpdatedAt, resolutionNote}` and returns `{reportId,
active, updatedAt, activeRestriction}`. The successful restored row has
`active=false`; combined scope `activeRestriction` can remain true. This path
locks only the independent restriction, rejects surviving reports and stale
timestamps with 409, and appends a restoration audit atomically. It never
reverses normal review's report-to-restriction lock order. Same-request retries
are idempotent; changed input under the same ID conflicts. The independent
`tbl_musician_feed_restriction_restore_audit` keeps one restoration per report
and a unique `(report_id, client_request_id)` without foreign keys to erased
reports or users.

### Moderation deployment

Before deploying this code, apply the repository's updated
`scripts/db/2026-09-11-musician-feed-delivery.sql`, followed by
`scripts/db/2026-09-13-musician-feed-moderation.sql`, alongside the other feed
scripts in their prerequisite order. The updated delivery script accepts
`RESTORED`; do not rerun an older copy whose status constraint excludes it.
The moderation script is repeatable: it adds version/review metadata, preserves
legacy version-zero rows without inventing reviewers, creates audit and
restriction tables/indexes, seeds the permission, backfills existing orphan
markers, and recreates the delete trigger. Reapplying the updated ordered
scripts preserves completed/restored decisions and audit rows. Its lock and
statement timeouts bound deployment waits; index creation and orphan backfill
still need a deployment window appropriate to the database size. Runtime
initialization is not a substitute for applying this migration.

## Sponsorship

Sponsorship is delivery metadata, not a separate copy of native content. The
feed contract uses an optional promotion envelope around a native target, or a
standalone creative target. Supported architecture targets are standalone,
Event, Collab, Backline, and Profile; Track/Media may be added later through the
same provider/renderer contracts.

Paid content is labelled "Sponsored"; editorial platform selection is labelled
"Featured"; platform announcements use their own disclosure. In the musician
feed, sponsored profiles are limited initially to musician, band, venue, and
studio profiles. Standalone sponsored posts have CTA, hide, and report controls
but no likes/comments. Target-backed placements keep native actions.

Sponsor insertion rules:

- never in the first two positions
- never consecutive
- variable gaps of six to ten organic items, averaging eight; never a fixed
  every-eighth-item pattern
- the gap is stable for the viewer, session anchor and placement ordinal;
  pagination/retries preserve the last placement position instead of rerolling
- when no eligible placement is available, continue organic content; after a
  late placement, start a full new gap rather than catching up with extra ads
- no daily total or per-campaign delivery quota; different creatives from the
  same campaign remain eligible, while already-delivered item/target identities
  cannot repeat within that feed session
- no duplicate organic and sponsored rendering of the same target in a session
- sponsorship never bypasses target visibility, status, eligibility, or
  relevance

The existing Promotion module is a migration starting point, not yet a feed
campaign system. There is currently no built-in sponsorship provider or mock
sponsor generation. Until admin-managed real campaigns are connected, the
feed continues with organic content only. The sponsorship provider interface,
placement metadata, native card support and insertion policy remain available
for that integration; removing test-world tooling does not remove this product
architecture.

Campaign integration must also add current campaign eligibility validation to
delivery replay, alongside the native target's visibility checks. Until that
authority exists, the replay guard fails closed for promoted items; the
insertion policy alone does not authorize a cached placement to be replayed.

## Extension contract

The feed is extended by registering two independent components:

1. a backend candidate provider/resolver that returns bounded, authorized
   candidates and validates them again at hydration time;
2. a frontend card renderer for the versioned item type.

Every new item family must also implement current replay eligibility validation
for its exposed identities, publication/target, and any primary social action.
These checks must be bounded and batched, independent of current ranking,
candidate windows, and delivery deduplication. A still-public item falling out
of a candidate window is not a reason to invalidate its replay.

The core mixer, pagination contract, feedback model, analytics envelope, and
sponsor insertion policy must not require rewriting when a new item family is
added. Unknown item types are never served to clients that did not advertise
support for them.

## Operational quality

- Before enabling retention on the updated schema, apply
  `scripts/db/2026-09-13-musician-feed-retention-lookup.sql` after delivery and
  feedback migrations. It adds delivery-leading indexes on durable reports and
  feedback, so `ON DELETE SET NULL` lookups do not repeatedly scan those growing
  histories when a delivery batch expires. Reports, evidence, decisions and
  preferences are retained. The repeatable transaction has a five-second lock
  timeout and sixty-second statement timeout; schedule regular index creation
  for the table size and write traffic. Hibernate index metadata is equivalent.
- Opaque, versioned cursors and a fixed feed-session anchor prevent ordinary
  pagination reshuffles.
- A successful signed continuation request is journaled atomically with its
  delivery rows. Retrying the same cursor, requested limit, and renderer set
  within the cursor/delivery TTL returns that exact committed page and tokens
  only while its exposed identities and content retain current visibility and
  eligibility. Conflicting request inputs at the same position fail closed.
- Replay rechecks identities and publication/target eligibility in batches.
  Privacy restrictions, deleted content, withdrawn approvals, closed or expired
  listings, events that have started, hide/mute/report feedback, and withdrawn
  primary like/comment/follow actions invalidate the cached page with
  `MUSICIAN_FEED_CURSOR_INVALID`; the client refreshes from the first page.
  Engagement counters may change without rebuilding an otherwise eligible
  committed page.
- Overthinking source privacy is resolved through its canonical viewer-aware
  service. Replay transactions are not marked read-only because this resolution
  acquires shared identity locks on PostgreSQL.
- Replay idempotency is continuation-only. Initial feed loads have no stable
  client request identity and a retry intentionally creates a fresh session.
- Cursor schema/algorithm changes, active-secret rotation, and cursor expiry
  return stable error `MUSICIAN_FEED_CURSOR_INVALID` so the client refreshes
  from the first page; an incompatible prior-deploy response is never replayed.
- All multi-source reads use projections/batched hydration and bounded pool
  sizes; no entity graph walking or per-card network/database calls.
- Impression, open, CTA, follow, save/apply, hide, mute, and report events carry
  item, target, reason, algorithm, session, and position identifiers.
- Database changes are additive and forward-compatible with the previous app
  version. Runtime seeders are not production migrations.
- Feed rollout is feature-flagged and fails closed per optional candidate
  provider; one broken optional source must not corrupt the whole page.
- Authenticated feed reads are rate-limited per user before ranking begins.
  Initial loads and continuations use separate Redis-server-time token buckets,
  while both atomically reserve the same sustained page budget. This prevents
  new-session churn from creating an unbounded delivery ledger. Telemetry has
  its own budget. Feedback POSTs and author mute/unmute writes share a separate
  per-account bucket, enabled in production: by default a burst of 20 and one
  token replenished every two seconds. This short-lived request budget is
  independent of feedback history size, page reads, and telemetry. It runs
  before the write service and is configurable through
  `SOUNDCONNECT_MUSICIAN_FEED_RATE_LIMIT_FEEDBACK_BURST` and
  `SOUNDCONNECT_MUSICIAN_FEED_RATE_LIMIT_FEEDBACK_REFILL`. Redis failures fail
  closed with HTTP 503; policy rejections
  use HTTP 429, and both responses include `Retry-After`.
- Existing module regression suites plus feed contract, authorization,
  determinism, pagination, dedupe, and query-count tests are release gates.
