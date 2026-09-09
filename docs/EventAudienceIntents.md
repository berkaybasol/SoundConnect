# Personal event plans and listener profile posts

Audience intent is a personal expression (`NONE`, `THINKING`, `GOING`), not an RSVP, ticket, reservation, check-in, attendance confirmation, or performer consent. There are no audience counts, participant lists, or analytics additions. Published listener posts have independent `EVENT_POST` conversations; existing `EVENT` comments remain attached to their event. Existing performer approval, performer calendar publication, and the disabled venue-reporting launch flag are unchanged.

## Eligibility and privacy

Authenticated, currently active and email-verified users with exactly one matching personal LISTENER or MUSICIAN role/profile may maintain a plan. Business, staff, conflicting-role/profile, inactive, and unverified accounts fail closed. Musicians can use either the event-detail or listener-post entry point, but cannot publish audience posts or attach notes.

The existing JWT listener-onboarding gate is unchanged: listeners who have not completed the visibility choice cannot access any audience endpoint until they complete it. Service-level private-state handling does not create an HTTP onboarding bypass.

Only a listener may explicitly publish a positive intent with an optional plain-text note. New plans are private by default. STANDARD visibility and a completed visibility choice are required for new publication/note edits. Ghost mode hides retained publication preferences without deleting them; STANDARD restores those preferences. A ghost listener may clear/unpublish or change only the positive intent of an already-published post while retaining the identical note; it remains hidden until STANDARD. Ghost mode never permits a new publication or edited note.

Public posts follow the existing **authenticated** listener-profile namespace (they are not guest-readable). Missing, inactive, unverified, wrong-profile-type, or visibility-choice-pending authors return 404. Ghost authors return an empty page with zero total. The existing backend has no account-block subsystem; this feature does not invent one. Future blocking must be applied before selecting/counting visible posts, consistent with the rest of public-profile privacy.

Events must currently satisfy public discovery eligibility: venue-origin, approved venue/calendar, active verified venue owner, valid location hierarchy, and non-null date/start. Musician-origin calendar events remain outside this audience surface. Invalid/withdrawn/deleted events are omitted from plan/post pages. By-ID owner state remains readable if a plan/tombstone exists, but event details are null and mutation capabilities are false; owners can clear or unpublish retained state. Unknown events without state return 404.

Event end follows the existing Istanbul calendar policy: explicit same-day end time, or start plus one hour for absent/earlier end. At the exact end instant the event is past. New positive choices, changed positive choices, new publication, and note edits are closed then. Accepted exact retries, unchanged state, unpublishing the same intent, and clearing to NONE remain allowed. Earlier public posts can remain visible as historical plans and carry `eventEnded=true`.

## API contract

All responses use existing `BaseResponse` and `Cache-Control: private, no-store`. Actor identity comes only from the authenticated principal. No actor ID is accepted in command JSON.

| Method | Endpoint | Purpose/default |
| --- | --- | --- |
| GET | `/api/v1/user/event-intents/{eventId}` | Own state; never creates a row |
| PUT | `/api/v1/user/event-intents/{eventId}` | Explicit full-state versioned mutation |
| DELETE | `/api/v1/user/event-posts/{postId}` | Remove this owner's exact publication; preserve private intent |
| GET | `/api/v1/user/event-intents` | Own non-NONE plans; `period=UPCOMING` |
| GET | `/api/v1/public/listener-profiles/{listenerProfileId}/event-posts` | Explicit posts; `period=ALL` |

Both lists accept `period=UPCOMING|PAST|ALL`, `page=0..1000`, `size=1..50` (default 20). They return the existing `PageResponse` fields `content,page,size,totalElements,totalPages,first,last,number`. Totals describe that user's eligible plan/post records, never event audience. Private UPCOMING sorts date/start ascending then UUID; private ALL/PAST sorts date/start descending then UUID. Public posts sort first-publication instant descending then UUID. In-place positive intent/note edits retain the publication instant; a fresh publication after unpublishing gets a new instant.

PUT requires exactly these four fields (unknown fields, duplicate keys, scalar coercion, and omitted fields are rejected):

```json
{"intent":"THINKING","publishedOnProfile":false,"note":null,"expectedVersion":0}
```

`NONE` requires `publishedOnProfile=false,note=null`. Notes are allowed only with explicit publication, normalized with Unicode whitespace stripping, blank-to-null, at most 500 Unicode code points. Invalid control characters/unpaired surrogates are rejected; newlines and tabs are allowed. The server treats notes as plain text. A positive-to-positive choice preserves publication/note only when the full request explicitly carries them. Clearing NONE always removes both. Private-first UI saves false/null, and optional publication is a second mutation using the returned version.

Private state fields: `eventId,intent,publishedOnProfile,note,version,updatedAt,eventAvailable,eventEnded,canSetIntent,canPublish,publicationVisible,event`. Initial state is NONE/version 0/null updatedAt. `publishedOnProfile` is retained preference, whereas `publicationVisible` reflects current author/event visibility. `canPublish` means new publication/edit capability, not whether an old post is visible. `event` is the existing `EventResponseDto` (or null when unavailable).

Public post fields: `postId,eventId,intent,note,publishedAt,eventEnded,event`. Private states also expose nullable `postId`, present only for a retained publication. There is no author-private version, unpublished preference, audience total, or participant identity list in a public post. Both pages reuse the scalar public event-card projection and a single bounded poster-decoration batch; no per-event entity graphs/member lists are loaded.

`postId` is an independent UUID, not the event ID. Positive intent/note edits and temporary ghost hiding preserve it. Unpublishing or clearing discards it; republishing creates a new UUID with a new conversation. Owner DELETE returns the updated private State, increments its version, removes the note/publication, and keeps GOING/THINKING. An old/deleted/foreign post ID returns the same 404 and cannot delete a replacement publication. The existing account quota applies to DELETE as well as PUT.

Common comment/reply and comment-like endpoints accept `EVENT_POST` with `targetId=postId`. The authenticated social endpoint policy remains in force. Target checks lock the current author, listener visibility, event eligibility and exact publication; ghost, unpublished, removed, invalid-author or unavailable-event posts are inaccessible through comments/replies/likes. Previously written comments remain stored under the discarded UUID but cannot become visible on a new publication. Existing `EVENT` comments are never moved or copied. Comment authors can still delete their own comments after the target is hidden.

## Consistency and failure handling

Mutations serialize on the live actor account, then acquire the listener visibility shared lock and the event shared lock. Account locking also fences first-row creation. The event read fence allows different actors to choose concurrently, while still excluding event deletion, schedule changes, and consent edits. Visibility shared locks coordinate with the existing ghost-mode writer. Visibility is read as fresh scalar values under that lock, not as a managed profile entity: production Open Session in View can otherwise reuse a stale STANDARD profile from the earlier authority preflight after a separately committed ghost transition. The full command must match `expectedVersion`; effective changes advance the version once. An exact desired-state retry is accepted only when the current version is `expectedVersion + 1`. Older ABA retries return 409 even if values happen to match again. Clearing retains a NONE version tombstone so late commands cannot resurrect a cleared plan. No-op requests do not create/advance rows.

Reads use one bounded repeatable-read transaction for eligibility, page/count, states, and batched cards. Their shared locks coordinate with current account/profile privacy writers. Mutations use the existing Redis connection with an atomic fixed-window account quota of 60 PUT attempts/minute. Account preflight happens before quota use; write authority is checked again in the mutation transaction. Redis/database failures fail closed and do not expose internal exception text.

| Code | HTTP | Meaning |
| --- | --- | --- |
| 9920 | 400 | Invalid semantic command/page bounds |
| 9921 | 409 | Stale version; reload state before the next choice |
| 9922 | 409 | Event has ended; reload state |
| 9923 | 429 | Mutation quota exceeded; honor Retry-After |
| 9924 | 503 | Storage/quota temporarily unavailable; Retry-After 5 seconds |

Malformed JSON uses the existing 400 error contract. Existing 401/403/404 codes apply to account/authority/target failures. Errors do not include current private state.

## Storage and deployment

The additive migration is `scripts/db/2026-09-08-event-audience-intents.sql`, registered last in `scripts/dev.ps1`'s ordered local migration list. It adds one `(user_id,event_id)` table, privacy/value/version constraints, and private/public partial indexes. It is replay-safe and uses bounded migration lock/statement timeouts. It does not alter existing event, performer, calendar, or analytics tables. The account FK cascades account deletion.

There is deliberately no event FK: intent/version tombstones survive event deletion. No event title/details are copied into the table. **Optional notes and retained preferences remain stored until the owner clears/unpublishes them or the account is deleted**, including after event deletion; hidden/deleted events are not listed. A caller retaining the event ID may fetch and clear the unavailable owner state. This bounded feature adds no automatic content-retention job or event-deletion hook. Any broader deleted-event note purge/discoverability policy requires a separate product decision.

Deployment requires the authorized database backup and explicit additive migration before serving the updated backend, then a backend restart and rebuilt frontend. Registering the script does not itself execute it. Do not run application migrations implicitly during tests. The venue analytics 90-day retention and reporting-default-off configuration are unrelated and unchanged.

The independent-publication follow-up is `scripts/db/2026-09-09-event-post-comments.sql`, registered after comment likes. It adds/backfills `post_id` only for published rows, adds publication/uniqueness constraints and widens the existing comment/like target enum checks. Reruns preserve assigned UUIDs, existing comments and accepted target types. Apply this migration explicitly before starting the updated binary; an older binary must not write publication state after this migration.

## Verification

`EventAudienceServiceTest`, `EventAudienceHttpTest`, `EventAudienceRateGuardTest`, and `EventAudiencePostgresTest` cover strict wire shape, principals/roles, ghost restoration and retained-intent changes, stale/ABA/exact retries, default-private publication, note limits, removed/malformed/ended events, Istanbul exact/fallback boundaries, constraints, account deletion, stable final/beyond pages, batched decoration, concurrent first writes, and the ghost transition fence. PostgreSQL tests use an explicitly wired non-reused PostgreSQL 16 Testcontainers datasource; URL/catalog are verified against the container before fixture/migration writes. No application database or local environment file is read or changed by the tests.

On 2026-09-08, Java 21, the following elevated command passed **631 tests in 69 suites**, with zero failures, errors, or skips (5m50s). It includes 55 audience tests and adjacent discovery, profile privacy, performer-publication, personal-role, and unchanged analytics regressions:

```powershell
.\gradlew.bat --no-daemon test --tests '*EventAudience*' --tests '*EventDiscovery*' --tests '*Event*Publication*' --tests '*Event*Performer*' --tests '*ListenerProfile*' --tests '*PersonalProfile*' --tests '*EventService*' --tests '*EventController*' --tests '*VenueAnalytics*' --tests '*Analytics*'
```

The follow-up verifies the standalone disposable-Redis quota script (concurrent atomic cap, account separation, non-extending rejection TTL), explicit audience migration registration order, and unchanged visibility-onboarding gate:

```powershell
.\gradlew.bat --no-daemon test --tests '*EventAudienceRateGuardRedisIT' --tests '*EventLocalSchemaMigrationOrderTest' --tests '*ListenerProfileChoiceGateTest'
```

The follow-up passed **10 tests in 3 suites**, with zero failures, errors, or skips (46s). `scripts/dev.ps1` passed a read-only PowerShell AST syntax check; `git diff --check` reported no whitespace errors. Application migrations/startup, local environment edits, and commit/push were not performed by this backend implementation task.

### Post-statistics audience quality audit

The focused audit reproduced and fixed two implementation defects without changing the API, product rules, or applied migration:

- A request-scoped EntityManager could reuse a preflight-era STANDARD profile after a separately committed GHOST transition. Fresh, shared-lock-protected scalar visibility reads now prevent a new publication or public read from trusting that cached entity. The PostgreSQL regression explicitly binds a request-scoped persistence context across the two transactions and separately verifies hidden public posts.
- The event fence originally used an exclusive lock even though audience writes never modify the event. It now uses `FOR SHARE`; a deterministic PostgreSQL `NOWAIT` regression proves an independent actor can save while event write/deletion locks remain excluded. The per-actor creation/version fence is unchanged. This is a concurrency-correctness check, not a production throughput/load claim.

The privacy regression failed before its fix; the independent-event-read `NOWAIT` probe also failed before the lock-mode fix. The final audit command is:

```powershell
.\gradlew.bat --no-daemon test --tests '*EventAudience*' --tests '*EventDiscovery*' --tests '*ListenerProfileRepositoryConcurrencyPostgresTest' --tests '*ListenerProfileChoiceGateTest' --tests '*PersonalProfileTypePolicyTest' --tests '*EventLocalSchemaMigrationOrderTest' --tests '*EventServiceImplTest' --tests '*EventPerformerRequestServiceImplTest' --tests '*EventProfilePublicationServiceTest' --tests '*ListenerProfileServiceImplTest'
```

The final XML report confirms **215 tests in 17 suites passed**, with zero failures, errors, or skips (2m28s), including 60 audience tests and 11 isolated PostgreSQL audience cases. These changes require a separately coordinated backend deployment/restart to affect an already-running application; this audit does not perform that action. Real-device multi-session UX and production capacity remain separate validation work. Existing deleted-event optional-note retention and lack of an account-block subsystem remain documented product-policy limitations, not silently changed behavior.
