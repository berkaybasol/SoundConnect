# MEDIA notification identity — 28 September 2026

> Bu teknik belgenin sürüm ve davranış bilgileri bu belge temizliği sırasında kaynak kodla yeniden doğrulanmadı; güncel kurulum veya kabul sonucu değildir.


Scope: existing `SOCIAL_LIKE` and `SOCIAL_COMMENT` events with `targetType=MEDIA`.
No native presentation, capability, allowlist, navigation, publication or engagement access rule changes.

## Storage and current identity

New events carry `actorId` (canonical UUID string) and numeric `mediaIdentityVersion=1`, in addition to the existing module/target/comment references. Event IDs, recipient selection, occurredAt and receipt/inbox transaction boundaries are unchanged. In particular unlike/re-like still uses the same deterministic event ID, and a founder/manager's own band engagement retains the existing self-owned fanout suppression. `emailForce=false` remains mandatory for the transactional producer.

The stored title is always `Bir kullanıcı içeriğini beğendi` or `Bir kullanıcı içeriğine yorum yaptı`. The stored message is the existing neutral instruction. No current or historical name, alternate profile name, avatar, comment text or media URL is added as an identity source. The database trigger and application writers both remove the explicitly listed identity snapshot fields.

Exact GET, paginated/filtered/recent inbox and final WebSocket/mail projection resolve the actor's **current canonical username**, or the existing resolver's permitted ghost/pending-choice identity. Scalar account SHARE locks precede the existing listener visibility locks; the delivery transaction holds them through WebSocket/mail transport. Inactive, unverified, erased, missing, malformed or unresolved actors never cause fallback to the old title or another profile. An ordinary resolver failure yields anonymous text. A database error may invalidate the transaction and fail the request; no successful stale identity response is returned.

The account fence rechecks versioned actors on creation and final delivery. Actor/recipient erasure removes the relevant inbox records in the real erasure transaction; push job foreign keys cascade, while receipt tombstones remain. MEDIA actor matching is exact and versioned. UUID text in another payload field is not ownership. The legacy broad matching policy for unrelated notification families is unchanged. A queued MEDIA WebSocket or mail delivery is suppressed if its inbox record was read or deleted.

The existing push planner/store already emits a generic, identity-free envelope for these types. This identity contract does not itself define native MEDIA presentation or rollout. An envelope prepared before a privacy change can reach the fake/real transport after its database transaction ends, but contains no actor, name, avatar, media or comment data. An already submitted external message cannot be recalled by this change.

## Legacy policy and migration

Apply `scripts/db/2026-09-28-media-notification-identity.sql` **before** this binary. It is a new forward step in the normal launcher registry, after V5. It adds one schema marker; V5 and its six capability markers are unchanged. The binary's `MediaNotificationIdentitySchema` startup gate requires the marker, validated constraint and enabled trigger. Startup executes no migration.

The migration only redacts the two MEDIA notification types. It preserves notification/source-event/recipient/target/comment IDs, read state, all timestamps, receipts and unrelated payload fields/types. Payloads without a trustworthy version-1 UUID become numeric version 0 with no actor reference. Usernames, event hashes, current likes and even potentially deleted comment sources are never reverse-mapped into identities. This deliberately anonymous historical behavior also applies to newly consumed old events.

Mixed version-1 records retain a valid actor reference; invalid/unknown/unversioned actor fields are discarded, not reassigned. Invalid JSON is rejected by PostgreSQL's JSONB input type. JSON scalar/array/null payloads without `targetType=MEDIA` cannot be identified as this scope and remain untouched by the migration. A strict canonical UUID syntax is used in both Java and SQL.

The migration is transactional and replayable without timestamp/read/receipt changes. Its trigger also sanitizes storage writes by an older binary during the SQL-before-binary transition. It cannot change an old JVM's already captured in-memory DTO. For a later authorized rollout, quiesce/stop old mutation and delivery workers, apply the SQL, then start the candidate and resume consumption. Do not operate mixed old/new delivery workers as a privacy-safe rolling deployment.

Existing dated migrations are untouched. Keep this additive sanitation step on a binary rollback: reverting it would restore permission for names to be stored. An older binary reads the anonymous stored title but does not provide the new current-identity projection, and is not an accepted replacement for the corrected delivery/erasure behavior.
