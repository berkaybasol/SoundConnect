# Overthinking lifecycle and music contract

Post deletion validates the active actor and owner, locks the post, and removes
reveal requests, comment likes, replies, root comments and post likes in the same
transaction. Comment, reply, like and reveal creation use the same post lock
boundary. A write admitted before deletion finishes before cleanup; a write that
waits behind deletion sees a missing target. Any failure rolls the cleanup back.

Internal music attachment requires an existing track with the expected owner
type and public, ready media. Attachment uses shared media and track locks before
writing the post. Track deletion follows media, track, then post lock order. The
database trigger detaches references and artist metadata for normal track removal
and band bulk deletion; foreign keys prevent new dangling track IDs. Media deletion
failure rolls back both the track removal and post detachment.

Published posts cannot be edited, including their text, visibility and music.
Likes and reveal consent continue to refer to the original publication. The
legacy PUT endpoint validates authentication and ownership, then returns HTTP
409 with error code `9412` (`OVERTHINKING_POST_IMMUTABLE`). The author can still
delete the post. Track deletion may detach unavailable music while preserving
the published text; stale edit forms are rejected as well. This policy requires
no schema change or migration.

`GET /api/v1/overthinking/reveal-requests/incoming/pending-count` requires
authentication and returns `BaseResponse.data` as `{ "count": 3 }`. The value
counts only the authenticated author's `PENDING` requests, across all pages.
It uses the existing `(author_id, status)` index and does not load requests or
profile data. Approval, rejection and post deletion reduce the count naturally.
This compatibility endpoint is not the unread indicator.

## Incoming inbox read state

`GET /api/v1/overthinking/reveal-requests/incoming/unread-status` returns
`BaseResponse.data` as `{ "hasUnread": true, "revision": 3 }`. Opening the
incoming requests tab acknowledges this exact snapshot with
`POST /api/v1/overthinking/reveal-requests/incoming/seen` and body
`{ "revision": 3 }`. Its response has the same status shape; it can remain unread
when a new request arrived after the acknowledged snapshot. Both endpoints derive
the author solely from the authenticated principal. Negative/missing revisions
are rejected; acknowledgements never move backwards or cover future revisions.

Read state is independent of pending/approved/rejected decisions and persists
across devices and application restarts. A single SQL snapshot returns the flag
and revision. An author-specific ledger serializes new request revisions with
acknowledgements. Only existing request/post rows beyond the seen watermark
produce the unread flag, so deletion cannot leave a phantom dot. Notification
delivery/replay/retention cannot reset it; only a new domain request increments
the revision.

Apply `scripts/db/2026-09-10-overthinking-inbox-seen.sql` before exposing these
endpoints. It adds a ledger table, request revision column, index and insert
trigger. Existing binaries remain compatible because revision assignment takes
place in the database. It can run while the API is active: short table locks
serialize the deterministic `created_at,id` backfill with writers, and the whole
transaction rolls back on the 10-second lock/60-second statement timeout. Back
up first and use `psql -v ON_ERROR_STOP=1`. It preserves existing request fields
and decisions. Existing requests begin unread because historical viewing was
not recorded; the first inbox opening acknowledges them. Rerunning the script
preserves assigned revisions and seen watermarks. No database reset is needed.

PostgreSQL tests cover initial/backfilled state, preserved row contents,
idempotent migration/retries, empty/deleted inboxes, unchanged pending counts,
same-count replacement, decision independence, acknowledgement racing a new
request, and transaction rollback. MVC tests verify authenticated author scope
and the explicit snapshot acknowledgement contract.

## Request withdrawal, viewer state and feed order

Feed and detail post responses include `revealRequestPending`, true only for the
authenticated viewer's existing pending request on that anonymous post. Author,
guest and already-approved projections return false. Pending IDs are resolved
in one bounded query per page, so refreshes and device restarts preserve state.

`POST /api/v1/overthinking/{postId}/reveal-requests` succeeds only for a new or
existing pending request. `DELETE` on the same route withdraws that viewer's
pending request; an absent request/post is an idempotent success. Both reject
already-approved/rejected requests with HTTP 409 and code 9409. Withdrawal and
author decisions acquire the same parent/child locks, so neither can overwrite
committed consent. A later new request has a fresh ID and inbox revision.

Withdrawal retains notification receipts before deleting delivered incoming
notifications. Claiming the same receipt serializes with an in-flight consumer;
delayed/outbox-replayed events cannot recreate the withdrawn notification. The
existing outbox audit rows remain intact. Published outbox retention now waits
for a matching durable consumer receipt, preserving cancellation's replay fence
even when a broker/DLQ holds an unconsumed event beyond the retention window.
This uses existing tables and requires
no additional database migration.

Incoming requester avatars use the shared public profile/media projection for
standard personal, studio and venue profiles. Ghost identity always takes
precedence, including its intentional missing avatar; standard private or
unavailable media is never used as a fallback.

`GET /api/v1/overthinking/feed?order=NEWEST|MOST_LIKED|OLDEST` applies the order
before database pagination. NEWEST is the default. Dates sort with an ID tie
breaker in the same direction; MOST_LIKED sorts global overthinking-like counts
descending, then creation time and ID descending, including zero-like posts.
`page` and `size` remain supported; conflicting generic `sort` parameters are
ignored. PostgreSQL tests cover viewer scope, withdrawal/approval, global ranks,
zero-like pages, stable ties and notification delivery/replay races.

## Listener profile shares

Only an active, email-verified account with exactly the LISTENER personal role
and exactly one listener personal profile may manage shares. ADMIN/OWNER,
musician and corrupt multi-personal combinations fail closed. A listener with
completed STANDARD visibility can publish their own or another source post;
ghost/pending listeners cannot publish. Ghost listeners can inspect their own
saved state and remove an existing publication.

Authenticated `GET /api/v1/overthinking/{postId}/profile-share` returns
`{postId,shareId,publishedOnProfile,note,publishedAt,canPublish}` inside BaseResponse.
`PUT` accepts only `{note?:string|null}`: trim whitespace, empty becomes null,
maximum 500 Unicode code points, control characters other than newline/tab are
invalid. A repeated identical PUT preserves ID, note and publication time;
another note conflicts with HTTP 409/code 9413. Published notes are immutable.
`DELETE /api/v1/overthinking/profile-shares/{shareId}` removes only this exact
owner's publication and returns null data. Missing/wrong-owner/stale IDs return
404/code 9415 and cannot delete a later republication or the original source.

Authenticated `GET /api/v1/public/listener-profiles/{profileId}/overthinking-posts`
accepts page 0..1000 and size 1..50 (default 20), newest publication first with an
ID tie breaker. PageResponse contains `content,page,number,size,totalElements,
totalPages,first,last`; each item is `{shareId,note,publishedAt,post}`. Restricted
ghost profiles return an empty page; pending/invalid/hidden profiles return 404.
All responses use `Cache-Control: private, no-store` because nested original
posts are projected for the current viewer. No identity snapshot or copy of the
source is stored. Like/comment counts, reveal state and Spotify enrichment are
batched for the page, never fetched per share.

Apply `scripts/db/2026-09-10-overthinking-profile-shares.sql` after a backup,
before enabling the new endpoints. It adds only a share table, indexes and
constraints, and records its migration ID. Existing users/posts/requests are
preserved. Cascades remove shares when the source, owner or listener profile is
deleted. Actor -> profile -> source locks serialize concurrent publication,
source deletion and visibility transitions. A restricted profile keeps stored
shares hidden until it becomes public again. No database reset is needed.

## Spotify references

Writes accept HTTPS `open.spotify.com/track/<22-character base62 ID>` URLs,
including the existing optional `intl-xx` locale prefix, shared-link query and
fragment. Storage uses the canonical URL without locale/query/fragment. Other
hosts, credentials, alternate ports, encoded or extra path segments, short IDs,
raw IDs and `spotify:` URIs are rejected. Spotify fields have storage-aligned
length limits; artwork hints must use an absolute HTTPS URL. Blank music fields
become null. Spotify metadata requires a Spotify track source, and multiple
sources remain invalid.

The public `spotifyArtistId` is a client hint. A local musician/band association
is written only if the existing Spotify client returns that artist ID for the
exact requested track. A provider error, unavailable artist IDs or mismatch
leaves local `artistId`/`artistType` empty while permitting the link and text to
save. This preserves the existing
optional-provider behavior; it adds no background job or provider cache.
Canonical metadata takes precedence on reads when available; bounded client
title/artist/artwork hints remain available during an outage and are never used
as account attribution evidence.

## Migration and validation

Apply `scripts/db/2026-09-09-overthinking-lifecycle.sql` with writers stopped,
after a backup and before starting the upgraded binary. It runs transactionally
with a 10-second lock timeout and 60-second statement timeout, repairs legacy
orphans and dangling track references, installs the trigger/foreign keys/indexes,
and records `2026-09-09-overthinking-lifecycle` in
`soundconnect_schema_migrations`. It clears previously unverified local Spotify
artist associations once, preserving the link, public artist hint and display
metadata. Reruns preserve associations established after the first application.
Stop on any SQL
error; use `psql -v ON_ERROR_STOP=1 -f <script>`.

`OverthinkingLifecyclePostgresTest` uses a non-reused PostgreSQL Testcontainer
with verified URL and catalog. It exercises deletion rollback, admitted/waiting
engagement and reveal races, shared attachment/removal locks, trigger behavior,
band bulk deletion, rejected edits preserving existing engagement and consent,
migration repair and reruns, and Spotify outage commit behavior. Music source unit tests cover URL,
field, metadata and authoritative artist matching contracts. No application or
production database or storage service is used by these tests.
