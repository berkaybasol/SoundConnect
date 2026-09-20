# Ekipman Pazarı: first release

The marketplace is an independent Backstage classifieds module. Musician, studio and venue accounts can publish instrument, audio and stage-equipment listings. Listener accounts, including legacy accounts with an additional professional role or profile, cannot access its listing, category or private media APIs.

There is no payment, order, shipping operation, seller rating, Backline import or new DM message protocol. Seller contact uses the existing two-person conversation. No Backline or Instrument tables or services are modified.

## Installation

Apply these migrations with `psql ON_ERROR_STOP=1` to the intended database before deploying the updated media worker and API:

1. `scripts/db/2026-09-20-marketplace-domain.sql`
2. `scripts/db/2026-09-20-marketplace-media.sql`
3. `scripts/db/2026-09-20-protected-image-thumbnails.sql`

These migrations are transactional and rerunnable. The local `scripts/dev.ps1` ordered migration list includes them. Production Hibernate `validate` does not create the JDBC domain tables. Automated migration tests use disposable PostgreSQL only.

The domain migration installs the independent catalog from `src/main/resources/marketplace-category-seed.json`: 13 groups and 112 product types. Category UUIDs derive from immutable codes using PostgreSQL `md5('marketplace:' || code)::uuid`. Replaying the migration preserves active/inactive decisions and updates reference names and ordering. Future category changes should add a reviewed migration and update the resource plus parity tests. There is no startup seeder that unexpectedly mutates production reference data.

Apply the additive migrations and worker DB/IAM grants first. Update every API/deletion writer with native image production disabled, drain older worker processes, then start the updated worker and enable the client feature. This combined rollout must include both the `MARKETPLACE` owner type and private-thumbnail deletion fences; do not run a new thumbnail producer alongside old deletion code. During rollback, drain thumbnail producers before rolling back API/deletion writers and retain the additive column, objects and durable pending rows. Current-account permission checks read the database on every request.

## Data and access

`modules/marketplace` owns its JDBC catalog, listings, saved listings, reports and report-audit records. It reuses the profile ownership resolver, location data, shared response/error envelopes, and existing private-media upload/deletion pipeline.

Listings store the seller's user identity and original profile type/ID. A recreated or transferred profile does not inherit authority to publish an old listing. Discovery verifies the seller's current account, roles, personal-profile identity and original profile ownership. An eligible owner can still delete an old draft after profile recreation, preventing obsolete drafts from occupying their quota permanently.

Every public service method verifies the current account in the database. User mutation paths lock the account and recheck eligibility before locking the listing. This serializes draft/publication quotas and retries. Listings use explicit versions; stale updates fail with HTTP 409. Creating a draft accepts a client UUID and is idempotent per owner. Prices and versions require JSON integers; fractional or numeric-string money values are rejected rather than rounded.

Private listing responses use `Cache-Control: no-store, private`. Photo responses contain asset IDs only. Photo access uses current account authorization and the existing short-lived private-media URL mechanism; the implementation does not claim to prevent a recipient from copying an expiring signed URL.

`seller.avatarUrl` is resolved from the listing's original musician, studio or venue profile on each read. One bounded media batch per result page uses the existing public/READY display-URL policy, preferring a thumbnail. Missing, private or unavailable avatars remain null; private listing photos are never used as the seller avatar.

## Protected image delivery

The existing image worker generates one aspect-preserving JPEG thumbnail (maximum 960 pixels, no upscaling, metadata stripped). It reads only READY immutable private originals and writes `thumbnail.jpg` beside that original under `protected/private-verified/`, including the winning verification attempt directory. The private bucket and `private,no-store,max-age=0` policy remain in force. Neither the source nor the thumbnail is published to the public bucket/CDN.

`thumbnail_storage_key` is an internal completion marker, never a public DTO field or URL. The existing authorized media access response returns `thumbnailAccessUrl` and `thumbnailExpiresAt` only after generation commits; it signs only the derivative in that asset's exact source directory. Access requests perform no storage HEAD, download or image processing. Older clients and assets without a thumbnail retain original-photo access; legacy client-writable private originals are not processed.

New uploads signal the existing bounded executor after commit. The rotating, bounded recovery scan retries missing variants after failure/restart without holding a database transaction during storage or native processing. Column-scoped compare-and-set rechecks READY status and the exact source; concurrent successful workers preserve their shared derivative. Both generic and marketplace deletion paths capture image producer grace, retain report references, and remove the deterministic thumbnail after producer fencing. Orphan recovery preserves the winning attempt subtree.

Apply the additive migration first, then deploy the API deletion/access changes before enabling protected thumbnail production on updated workers. Drain old media/deletion worker processes during this rollout: an old deletion worker does not understand private image producers. The isolated worker IAM example in `docs/MediaModule/IsolatedMediaWorker.md` includes narrowly scoped private thumbnail write/compensation grants. No additional service, queue, bucket or port is required.

## Listing rules

- Product condition: `NEW` or `USED`; no cosmetic/working condition fields.
- Publication requires title 5–120 characters, description 10–4000, an active leaf category with active parent, positive TRY price, district, delivery preference and 1–8 ready private images.
- Price is integer kuruş, from 1 to 100,000,000,000. Brand/model are optional bounded strings.
- District refers to existing location data; its city is derived. Combined city/district search filters must agree.
- Drafts may be incomplete. Published and withdrawn edits must remain complete.
- State transitions: `DRAFT → PUBLISHED`, `PUBLISHED → SOLD | WITHDRAWN`, `WITHDRAWN → PUBLISHED`; moderation can remove a reported listing.
- Initial publication time is preserved through edits and republishing. Default discovery is descending publication time and ID. Price sorting also has a stable ID tie-breaker.
- Limits: 30 drafts and 20 published listings per account, with account locks preventing concurrent requests from exceeding the limits.
- Pages accept sizes 1–50 and page numbers 0–1000. Text search escapes SQL wildcard characters.
- Draft deletion first detaches photo references, then requests durable media deletion and deletes the listing in the same transaction. Saved draft photos are not removed by an arbitrary draft-expiry policy.

## APIs

The authenticated base is `/api/v1/user/marketplace`. Responses use the existing `BaseResponse.data` and `PageResponse` contracts.

- `GET /categories`
- `GET /listings` with `q`, `categoryId`, `cityId`, `districtId`, `condition`, `minPriceMinor`, `maxPriceMinor`, `sort`, `page`, `size`
- `POST /drafts` with `{clientRequestId}`
- `GET /listings/{id}` and `PUT /listings/{id}`
- `POST /listings/{id}/publish`, `/sold`, `/withdraw` with `{expectedVersion}`
- `DELETE /listings/{id}?expectedVersion=N` for a draft
- `GET /my-listings`, `GET /saved`
- `PUT /listings/{id}/saved`, `DELETE /listings/{id}/saved`
- `POST /listings/{id}/reports` with `{reason,description,clientRequestId}`

Only published listings are visible to other users. Owners can inspect their own inactive records. Saving is idempotent; saved lists omit unavailable listings.

## Moderation and evidence

`/api/v1/admin/marketplace/reports` requires current `MANAGE_MARKETPLACE_REPORTS` permission and rejects listener identities even when they hold that permission. `GET` supports status and pagination. `POST /{reportId}/review` accepts `{expectedVersion,decision,resolutionNote}`; decisions are `DISMISS` and `REMOVE_LISTING`, with a required 5–1000 character resolution note.

One report per reporter/listing is allowed. Request IDs are checked against the original reason, description and listing. The report stores an immutable listing snapshot and normalized photo references. Editing a listing cannot erase the report evidence. Each resolved report creates an audit record; replaying the same successful decision does not create a second audit.

Account/listing deletion sets the report's identifying foreign keys to null rather than deleting moderation evidence. Reported photos remain protected and accessible to authorized moderators even after the listing is gone. There is no automatic evidence-retention deletion policy in this release.

## Verification

`MarketplacePostgresTest` runs only against disposable Testcontainers PostgreSQL. It verifies all three migrations repeatedly, exact catalog parity, current-role/profile boundaries, concurrent creation/idempotency/quota enforcement, lifecycle/version/ownership rules, filters/pagination, report snapshots/audits and media-reference retention. No application configuration or local database credentials are loaded.

`MarketplaceControllerAuthorizationTest` checks every user route against listener and mixed-role identities, allowed professional roles, private caching, and explicit administrator permission. `MarketplaceRequestContractTest` checks integer price/version parsing. Separate marketplace media tests cover upload access, photo ownership/state/privacy, quotas, immutable evidence references and durable cleanup.

`MarketplaceHttpSecurityPostgresTest` additionally uses the actual JWT filter, database-backed account loading, security chain, controllers and domain services with disposable PostgreSQL. MockMvc opens no server port; only external storage, messaging, mail and asynchronous delivery are replaced. It checks invalid/expired bearers and disabled accounts, current roles/permissions with the same previously issued bearer, all three seller profile types, cross-owner drafts, invalid money/version payloads, protected-photo visibility, and report/moderation retries with one audit and retained evidence. Tokens and mock request headers are never printed.

CI runs `scripts/verify_marketplace_test_reports.py` after the backend suite. Missing reports, zero executed cases, failures and Docker-unavailable skips in the critical HTTP/database/media suites fail the gate. Local Docker-less convenience skips therefore cannot produce a successful marketplace quality gate.
