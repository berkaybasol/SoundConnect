# TableGroup API contract

The first production release uses the following canonical TableGroup contract:

- `meetingAt` is the user-selected meeting instant. It must be in the future
  and no later than 24 hours after the server-owned `startAt`. Create JSON must
  send it as an offset-aware RFC 3339 string (for example `2026-09-02T20:00:00+03:00`
  or its UTC `Z` equivalent). Numeric epochs, zone-less local timestamps, and
  the retired create-field alias `expiresAt` are rejected.
- Response `expiresAt` is not the selected meeting time. It is the technical
  lifecycle cutoff used for automatic closure and authorization. For every new
  table the server sets it to exactly `startAt + 24 hours`, independently of
  `meetingAt`. Existing rows retain their migrated lifecycle value. `startAt`,
  `meetingAt`, `expiresAt`, participant `joinedAt`, message `sentAt`, and
  `deletedAt` are returned as RFC 3339 instants.
- Create requires `description`. The backend trims Unicode boundary whitespace;
  the result must be non-blank and at most 280 Unicode code points. Every new
  aggregate uses the collision-safe, length-framed v2 idempotent-create
  fingerprint and persists a non-null request key. Response schema keeps
  `description` nullable only because retained terminal legacy rows may still
  contain `null`; new rows and writes cannot introduce it.
- Active-list paging is server-controlled: default size 20, maximum size 50,
  and maximum page number 1000. Client sort input is ignored; rows are ordered
  deterministically by `(createdAt DESC, id DESC)`. `cityId` is optional: when
  it is omitted, the endpoint returns the active, unexpired global feed across
  all cities. `districtId` requires `cityId`, and `neighborhoodId` requires
  both its matching `districtId` and `cityId`. The database requires a
  non-null UTC wall-clock `createdAt` for every table. A missing prelaunch
  value is backfilled only from that row's authoritative
  `startAt` instant; rows lacking both clocks block the migration for manual
  reconciliation.
- `GET /api/v1/table-groups/mine` is the authenticated, paged inbox projection.
  It returns only active, unexpired tables where the principal is the owner or
  an `ACCEPTED` participant. Clients must use it for a user's table inbox;
  reconstructing membership by scanning public city feeds is not supported.
- Table ownership and participation always use the authenticated personal user
  UUID. `ROLE_VENUE` and `ROLE_STUDIO` accounts cannot create a table or apply
  to one (`403`, code `9124`); a multi-role account is denied when either role
  is present. Historical accounts that own a Venue or have a Studio profile
  are denied by the same rule even if their role join row is missing. Other
  existing individual roles retain access. A musician acts
  as their user identity: the create/join DTOs accept no `bandId`, and there is
  no band-representation TableGroup endpoint. Table metadata also never falls
  back to a band, venue, or studio avatar when the personal profile has no
  image. Eligibility is rechecked under lock before join persistence and again
  when the owner approves a pending applicant.
- A later institutional role grant does not destructively cancel an already
  active table or remove an already accepted member. Those memberships retain
  their existing lifecycle access until the table's bounded 24-hour expiry,
  while new creates, applications, and still-pending approvals are denied.
- Active-list responses contain accepted participants plus the authenticated
  caller's own pending application. Join notes are always redacted in lists.
  Usernames are batch-enriched. The list's nullable `ownerProfileImageUrl` is
  resolved in bounded batches from the owner's MUSICIAN, LISTENER, ORGANIZER,
  or PRODUCER personal profile (in that order) using the shared public/READY
  media display-URL policy. BAND, VENUE, STUDIO, and legacy user-picture data
  are never list-avatar fallbacks. Participant image fields remain nullable and
  omitted from lists. Detail responses resolve owner/accepted-member profile images;
  owner-visible pending applicants use the same fallback. Since a detail can
  expose the owner plus up to 50 pending applicants, avatar projection is split
  into safe batches of at most 50 users and fails open per batch.
- Detail responses contain accepted participants. The owner additionally sees
  pending applicants and their notes; a pending applicant sees their own row
  without its note. Rejected, kicked, and left history is not returned.
- Inactive or cancelled table details are visible only to the owner and users
  who were accepted members. Other authenticated callers receive not-found.
- Pending applications do not reserve capacity. Capacity is checked when an
  application is approved, under a database write lock; the owner counts as an
  accepted participant. At most 50 pending applications may exist for one
  table.
- One personal user may own at most one logically open table. A different
  create request is rejected with HTTP `409`, code `9127`, until the existing
  active, unexpired table is cancelled; scheduler-lagged rows whose expiry has
  already elapsed do not block creation. An exact idempotent create replay is
  resolved before this rule and still returns its original aggregate. The
  owner/status/expiry lookup is backed by
  `idx_tablegroup_owner_status_exp_id`; expiry is re-evaluated against a fresh
  timestamp after row locks are acquired so lock waits cannot create a false
  conflict.
- Applying to another table does not close the applicant's own table. When the
  target owner actually approves the application, every active, unexpired table
  owned by that applicant is cancelled in the same transaction. This reuses the
  normal cancellation cleanup: the active game is terminated, games and chat
  are purged, accepted members receive durable cancellation notification
  intents, and unread state is cleared after commit. If any step fails, neither
  the acceptance nor the automatic cancellation commits.
- A rejected, kicked, or departed user may apply again. Reapplication revives
  that user's existing participant row with a new timestamp and note.
- Repeating an already-completed approve, reject, leave, kick, or cancel action
  is a side-effect-free success when the resource is already in that exact
  target state. Conflicting lifecycle transitions remain errors. Participant
  transition idempotency lasts only while its bounded terminal row is retained;
  after privacy cleanup, a retry receives not-found. Approval first verifies
  that the supplied user is actually a pending/accepted participant, before it
  locks that user's shared lifecycle row. An already-accepted retry remains a
  success even if that account later acquired an institutional role/profile;
  eligibility is applied only to a still-pending decision.
- A supplied `venueId` must identify an approved registered venue whose
  city/district/neighborhood matches the request.
- Registered venue autocomplete is available only to authenticated clients at
  `GET /api/v1/table-groups/venue-options?q=...&limit=8`. The trimmed query is
  required and must contain 2-64 characters; `limit` defaults to 8 and must be
  between 1 and 10. The standard `BaseResponse.data` is a list containing only
  approved venues with a complete, internally consistent
  city/district/neighborhood hierarchy. Each item contains exactly `id`,
  `name`, nullable `profilePictureUrl`, `address`, `cityId`, `cityName`,
  `districtId`, `districtName`, `neighborhoodId`, and `neighborhoodName`.
  The picture is resolved from the venue-specific profile through the public,
  ready media display URL contract; an absent profile, absent picture, or
  non-displayable media asset produces `null` without removing the venue from
  the results. Clients render their local venue-avatar fallback for `null`.
  Results are ranked by
  case-insensitive exact name, prefix, then contains match, followed by stable
  name, city, district, neighborhood, address, and ID ordering. Duplicate
  venue names remain separate ID-bearing options.
- Venue input is optional and remains exclusive when present: omitting both
  `venueId` and `venueName` creates a table without a venue; selecting a
  registered option sends its `venueId` and a null `venueName`; typing without
  selecting sends a null `venueId` and the custom `venueName`. Supplying both
  request fields is rejected. Text equal to a registered venue name is never
  linked implicitly. For a selected option the backend persists the registered
  venue's canonical name in `venueName` as a display snapshot. Responses expose
  nullable `venueId` and `venueName`; `venueId` remains the only registered-link
  signal.
- Equivalent create retries by the same owner are replayed through a persisted
  non-null request fingerprint, so a response timeout does not create a
  duplicate table. This is server-side and requires no new mobile header.
- Chat is available only while the table is active and unexpired, and only to
  the owner or accepted participants. An authenticated membership denial is
  `403`, never an authentication-invalidating `401`. Client messages are
  `TEXT`, at most 1000 characters, and subject to per-user and per-table rate
  limits. A table stores at most 10,000 live messages. The first-release write
  path is the authenticated REST `POST`; TableGroup STOMP is receive-only so
  every write has an application acknowledgement and stable error response.
  Every client-authored `TEXT` payload requires a UUID `clientMessageId`.
  For the same table and authenticated sender, an exact retry of the same
  trimmed `content` and effective `messageType` returns the original server
  message without consuming rate-limit/Redis capacity and without a second
  unread increment, WebSocket frame, or sent metric. A PostgreSQL
  transaction-scoped advisory lock serializes the `(table, sender,
  clientMessageId)` key across application nodes, so a retry that arrives
  before the original transaction commits waits for that result before any
  quota or aggregate-row lock. The write transaction pins `READ_COMMITTED`
  isolation so its post-lock lookup observes that commit regardless of the
  database role's default. Once persisted, the acknowledgement remains
  replayable even if the table lifecycle subsequently changes.
  Reusing that key with a different semantic payload returns conflict code
  `9128`. Server-authored `GAME` and `SYSTEM` rows may keep the field null.
  Responses therefore expose nullable `clientMessageId`, while all client
  `TEXT` copies echo the required key so REST/history/realtime copies reconcile.
- Chat page zero is the newest page and rows are returned by
  `(createdAt DESC, id DESC)`. Chat page size is at most 100 and page number at
  most 1000. The unread counter is reset only after a successful page-zero
  transaction.
- Unread counts are an approximate, best-effort badge. A message commit racing
  a page-zero read can be coalesced by the Redis reset/increment order; clients
  must use database-backed page-zero history as the authoritative view.
- Chat rows are removed in bounded batches 30 days after table expiry by
  default. Terminal participant rows are capped at 100 per table and removed
  by the distributed cleanup job seven days after their terminal-state
  timestamp. Cancellation deletes chat immediately. These are destructive
  retention rules and must be reviewed against erasure and legal-hold policy
  before release.

## Who pays game

The first chat game topic is `WHO_PAYS` ("Hesap Kimde?"). It is available in
three modes: `ROCK_PAPER_SCISSORS`, `DICE`, and `VOTE`. Game commands have the
same active, unexpired, accepted-member authorization boundary as chat. The
creator joins automatically, and at most one `LOBBY` or `IN_PROGRESS` game may
exist for a table at one time.

Compatibility is cohort-gated. A legacy Flutter client can render `GAME` only
as a static text fallback and does not apply same-message revision updates, so
interactive game tables must require the minimum supported game-aware client.
This is separate from the backend mixed-version rollout gate.

The game endpoints are rooted at
`/api/v1/table-groups/{tableGroupId}/chat/games`:

- `POST /` creates a game from `{ "requestId": <uuid>, "mode": <mode> }`.
  The request ID is a durable idempotency key scoped to the table and creator;
  retrying it returns the same game rather than creating a second card. A new
  game responds with HTTP `201`.
- `GET /active` returns the active GAME message or `null` when none exists.
- `GET /{gameId}` returns the retained GAME message for that game.
- `POST /{gameId}/join` and `POST /{gameId}/leave` change voluntary lobby
  participation. Repeating the already-achieved state is side-effect-free.
- `POST /{gameId}/start` lets the creator start early after at least two
  players have joined. Otherwise the lobby advances when its deadline is due.
- `POST /{gameId}/cancel` may be issued by the game creator or the table owner.
- `POST /{gameId}/actions` accepts
  `{ "requestId": <uuid>, "action": <action>, "targetUserId": <optional uuid> }`.
  `targetUserId` is required for `VOTE`, must be absent for RPS and `ROLL`, and
  may be omitted (or supplied as the caller's own ID) for `VOLUNTEER`; the
  backend always normalizes a volunteer target to the caller.

Every endpoint uses the standard `BaseResponse` envelope. Its `data` is a
`TableGroupMessageResponseDto` whose nested `game` member is authoritative;
only `GET /active` may return `data: null`. Join, leave, start, and cancel have
no request body.

Every successful mutation returns the server-owned `GAME` chat message. Its
normal message fields remain present, and its `game` member has schema version
1, a monotonically increasing `revision`, server time, phase deadlines,
players, per-player `hasActed`, revealed actions, and terminal result fields.
The same message ID is updated throughout the game, so clients must retain the
higher game revision when REST and STOMP delivery race. Clients must never
derive an outcome locally.

The lobby lasts three minutes. Each RPS, dice, vote, or vote-tie action window
lasts 20 seconds. A command at or after its authoritative server deadline is
late even if the bounded scheduler has not transitioned the game yet. Reads
and commands reconcile a due game under the same locks, so reconnects do not
surface a stale revision between scheduler ticks. Players who do not act are
marked `TIMED_OUT`. A game is cancelled when fewer than two eligible players
remain. A still-unresolved game is cancelled after round 20 with
`MAX_ROUNDS_REACHED`. Table cancellation or expiry also terminates the active
game.

Cancellation reasons are stable wire values. User-driven cancellation is
`CANCELLED_BY_CREATOR` when issued by the game creator and
`CANCELLED_BY_OWNER` when a different table owner moderates the game. Lifecycle
reasons are `CREATOR_LEFT`, `PLAYER_LEFT`, `PLAYER_REMOVED`,
`LOBBY_EXPIRED_NOT_ENOUGH_PLAYERS`, `NOT_ENOUGH_PLAYERS`, `TABLE_CANCELLED`,
`TABLE_OWNER_JOINED_ANOTHER_TABLE`, `TABLE_EXPIRED`, and `MAX_ROUNDS_REACHED`.
Clients should map these values to
localized copy and use a neutral fallback for unknown future values.

RPS choices remain hidden until the round resolves. One gesture or all three
gestures cause a replay; with two gestures, the winning gesture is safe and
only the losing players continue. Dice values are generated by the backend,
never accepted from the client; the lowest value pays and tied lowest players
roll again. Voting accepts one immutable choice per player. A normal vote must
target an active game player and may target the caller. `VOLUNTEER` is the
separate explicit honour choice shown by clients as `😎 Ben ödeyeceğim`. Vote targets and totals remain hidden while
the vote is open. When it resolves, every voter-to-target choice is revealed.
At a vote deadline, votes cast by or for a player who timed out are excluded
from the tally. Tied vote targets enter `VOTE_TIE_DICE`, where the tied players
roll and the backend again resolves the lowest value.

The terminal outcome is `ASSIGNED` unless the selected player submitted
`VOLUNTEER`, in which case it is `VOLUNTEER`. The canonical result copy is:

- Assigned: `Geçmiş olsun @username! Masan tarafından hesabı ödemekle
  cezalandırıldın. Umarız ipin ucu çok kaçmamıştır. 😄`
- Volunteer: `SoundConnect ve masan, sadakatini takdir ediyor! @username hesabı
  gönüllü olarak üstlendi. 😎`

User-created chat requests remain `TEXT`-only; clients cannot spoof a `GAME`
message or its result. Each game owns exactly one linked GAME chat row. Deleting
the game cascades to its players, actions, and card without deleting ordinary
chat. Table cancellation removes games immediately. Expired-table game rows
and username snapshots follow the same bounded 30-day retention window as chat
and are purged together.

Admission paths first serialize on the personal user row. When approval also
needs the applicant's owned table, the target and owned aggregate rows are
locked in deterministic UUID order; this avoids cross-approval user/table lock
cycles while preserving per-table capacity serialization. The expiry worker
selects oldest candidates in bounded batches but acquires the selected rows in
that same UUID order, and approval uses a fresh post-lock time for expiry
decisions. Other lifecycle writes and chat sends remain serialized per table. A transactional
outbox makes the state change and notification *intent* atomic. Dispatch is
at-least-once with stable event IDs, an idempotent notification consumer,
bounded retries, and an observable `DEAD_LETTER` state; it is not an
exactly-once delivery promise. WebSocket delivery and unread Redis state are
best-effort projections after database commit, while REST chat history remains
the recovery source of truth.

Game writes use the same table-first aggregate lock order, followed by the game
row lock. Game state and its chat card commit atomically. Game WebSocket frames
are also best-effort after-commit projections through the shared broker relay;
the active-game endpoint and database-backed chat history are the recovery
sources. The notification outbox is not used as a realtime game-event log.
