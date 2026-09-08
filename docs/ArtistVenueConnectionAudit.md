# Artist–venue connection audit — 2026-09-07

## Changes

Connection creation now requires exactly one artist identity, consistent with `requestByType`. Musician and band requests authorize the caller before checking private pending/accepted request state. Already accepted pairs reject another create or acceptance of a historical pending request with `REQUEST_ALREADY_ACCEPTED` (1502). Request messages are validated at the existing 255-character storage boundary before reaching the service.

Mutation locks serialize concurrent create, accept, reject, cancel and disconnect operations. A band target follows the existing aggregate order `Band -> connection request -> Venue`; creation skips the not-yet-existing request lock. Personal targets lock `connection request -> Venue`. All creates acquire the venue lock, covering pairs with no row yet. Band preflight reads only the scalar target ID before locking, avoiding stale managed request or membership snapshots while waiting.

Following the explicit product approval, new create and accept operations require both sides to have ACTIVE, email-verified accounts. For a musician this is the profile owner; for a venue this is its owner. A band has no independent account/status: at least one current ACTIVE FOUNDER or MANAGER must have an eligible account, and the acting representative must also be eligible. The existing manager delegation is preserved, so an eligible manager can act when a founder is inactive. A sorted scalar `FOR SHARE` account query rechecks eligibility after waiting for a concurrent account update and holds it until the connection transaction commits. Unavailable parties return HTTP 409 / `REQUEST_PARTICIPANT_UNAVAILABLE` (1510). Existing requests and accepted links are not automatically removed; reject/cancel/disconnect remain available to the otherwise authorized active party.

`MusicianProfile` now uses stable UUID equality and a constant class hash rather than mutable display/relationship fields. The previous implementation collapsed distinct, identically displayed musicians in a venue's set and could strand a renamed musician during removal. The three minimal counterexample tests failed against the old implementation before the fix. Both sides of the band/venue relationship are now updated in memory on accept/disconnect; previously an already loaded `Band.activeVenues` remained stale within the transaction even though the owning venue side changed.

Creation/decision inbox notifications use `TransactionalNotificationService.persistInCurrentTransaction`. Inbox write failures propagate and roll back the domain transaction. Realtime badge/cache/socket projection runs after commit. Generated titles are bounded to the 160-character inbox limit. Notification target selection is unchanged: `bandId` identifies a band even when `requestByType=VENUE`; notification event IDs are fresh per recipient.

## Additive private page API

All paths are relative to `/api/v1/artist-venue-connections`:

| Path | Authorization |
| --- | --- |
| `GET /musician/{musicianProfileId}/page` | Profile owner |
| `GET /band/{bandId}/page` | Current ACTIVE band member |
| `GET /venue/{venueId}/page` | Venue owner |

Parameters: `status` optional (`PENDING`, `ACCEPTED`, `REJECTED`), `incoming` optional boolean, `page=0` (0–10000), `size=20` (1–100). Invalid bounds return HTTP 400 / `REQUEST_PAGE_INVALID` (1509). Incoming means recipient-side for the chosen scope. `incoming=false` means sender-side; omission includes both. Filtering occurs in SQL before counts and paging.

`BaseResponse.data` contains `content`, `page`, `size`, `totalElements`, `totalPages`, `first`, `last`, plus compatibility alias `number=page`. Rows retain existing connection DTO fields and add `musicianProfilePictureUrl`, `musicianUsername`, and `musicianDisplayName`. Display name uses the current trimmed stage name, falling back to current username. The artist UUIDs remain separate: exactly one of `musicianProfileId` or `bandId` is populated. No user entity, contact details or membership metadata is returned.

Ordering is `createdAt DESC, id DESC`. Each page uses a read-only REPEATABLE_READ transaction so its content and total count share one snapshot. New pages use scalar projections for request/artist/venue identity and one batched public READY-media lookup. No per-row full musician profile fetch is necessary. Legacy list paths remain complete and compatible; they batch their band/venue image enrichment rather than silently truncating results.

Deployment performance indexes are supplied in `scripts/db/2026-09-07-artist-venue-request-pages.sql`. The file is source-only and was not applied to the running database. It uses `CREATE INDEX CONCURRENTLY` with autocommit and must not be wrapped in a transaction. The indexes are non-unique and do not repair or discard historical data.

## Verification

`ArtistVenueConnectionRequestServiceImplTest` covers identity ambiguity, profile/band authority, private-state probing, accepted duplicates, stale disconnected IDs, private page access/bounds, and batched current identity/media.

`ArtistVenueConnectionRequestControllerTest` verifies the additive page envelope, direction forwarding and defaults. `ArtistVenueConnectionRequestConcurrencyPostgresTest` uses its own disposable Testcontainers PostgreSQL instance, with external notifications/media mocked. It covers conflicting create/decision transactions, reconnection/stale IDs, domain rollback after inbox failure, direction/status filtering, complete legacy lists, and deterministic paging across equal timestamps. It never targets the developer's database. Test execution results are recorded in the overall audit handoff; this document does not imply that a skipped Docker test passed.

The deeper transition suite adds `ArtistVenueConnectionTransitionMatrixTest`: all four request directions (musician to venue, band to venue, venue to musician, venue to band), three persisted statuses, four actions and sender/recipient/outsider callers form 144 explicit combinations, plus missing-request cases. The relationship assertions keep personal and band identities independent and verify notification recipients for successful decisions. This finite matrix covers the defined state machine, not every possible deployment, historical corruption or unrelated service operation.

| Action | Authorized caller | Required state | New state / relation |
| --- | --- | --- | --- |
| Create | Sender; musician owner, venue owner, or ACTIVE band founder/manager | No PENDING or ACCEPTED request for the pair; both parties eligible | New immutable request ID, PENDING; no link |
| Accept | Recipient; same representation rules | PENDING; no other ACCEPTED pair; both parties eligible | ACCEPTED; artist/venue join added on both sides |
| Reject | Recipient | PENDING | REJECTED; no link |
| Cancel | Sender | PENDING | REJECTED; no link |
| Disconnect | Either represented party | ACCEPTED | REJECTED; only this artist identity's link removed |
| Read history/page | Musician owner, venue owner, or current ACTIVE band member | Any persisted state | No mutation |

The disposable PostgreSQL suite additionally covers two same-named musicians remaining distinct; lazy-proxy equality without initialization for musician, band and membership identities; already-loaded inverse relationship consistency; inactive/unverified account create/accept rejection with cleanup preserved; active manager/inactive founder representation; cancellation versus acceptance; disconnect versus reconnect with an old request ID; manager removal versus acceptance; account deactivation versus create/accept; and real band deletion versus acceptance in both lock-acquisition orders. PostgreSQL-generated lock SQL was inspected: entity write locks use `FOR NO KEY UPDATE`, so the initially suspected conflict with foreign-key key-share checks was not treated as a confirmed defect.

## Preserved rules requiring product decisions

- Connection management currently permits ACTIVE FOUNDER or MANAGER. Event public representation currently permits only ACTIVE FOUNDER. Unifying these rules requires an explicit delegation/authority decision; this change preserves both existing rules.
- All ACTIVE band members can read the band's entire connection request history, including requests from before they joined/rejoined. They may receive new decision notifications concerning those older requests, but past notifications are not retroactively delivered on joining. No membership-tenure filter exists for band connection history. This is separate from tenure-scoped personal event publication and has not been changed.
- Cancel, reject and disconnect all persist `REJECTED`. Cancel/disconnect do not emit a new notification. Distinguishing these outcomes and deciding whom to notify needs a product choice.
- A disconnected band relationship does not automatically disconnect any member's personal relationship. These are separate identities and remain independent.

## Remaining operational limits

The current local database's read-only inventory found zero duplicate ACCEPTED pairs, zero duplicate PENDING pairs, zero requests with both/neither artist identity, zero ACCEPTED rows without a join and zero joins without an ACCEPTED request. No user IDs or record contents are retained in this document, and no cleanup was necessary or performed. This only describes the inspected local snapshot, not other deployments. If another database contains historical accepted duplicates or inconsistent joins, an agreed reconciliation migration is still required; the new mutation guards are not a historical repair job.

Legacy complete-list routes remain unbounded for older clients and public-profile consumers. New management UI should exclusively use page routes; eventual legacy retirement requires a client rollout. Offset pages are deterministic for a fixed dataset, but inserts/removals between calls can shift offsets. Clients should refresh from page zero after mutation or total-count drift.

Venue locking intentionally serializes connection writes for a venue. This is a correctness tradeoff for current workloads; very high write volume may warrant a dedicated pair-lock key or connection aggregate plus database uniqueness after historical reconciliation.

Musician profile public responses already carry `activeVenueConnections` with venue ID, name and public READY avatar, so navigation must use the UUID rather than the legacy name-only `activeVenues`. They also expose full active band DTOs through `getBandsByUser`; public band member filtering remains the Band module's responsibility. Those profile reads still depend on existing lazy-loading/session behavior and can perform work proportional to the number of bands/venues. The new private page API avoids that path.
