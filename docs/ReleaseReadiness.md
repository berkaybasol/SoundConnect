# Release readiness and database migration gate

The Studio frontend/backend implementation has been reviewed and hardened at code level, but the repository as a whole is suitable for local feature development only after the automated quality gates pass. A production or staging deployment remains blocked until the database, messaging, observability, and object-storage gates below are proven against an authorized production-like environment.

## Why there is no generated baseline in this change

The current project has no authoritative, versioned schema history. Hibernate entity metadata and an H2 test schema are not a safe substitute for the live PostgreSQL schema: names, indexes, constraints, enum representations, extensions, and historical drift may differ. Creating a speculative Flyway baseline would make deployments appear safe while potentially skipping required changes.

Before the first deployment:

1. Capture a schema-only PostgreSQL dump and a data-quality report from each target environment.
2. Reconcile the dump with the current entities and repository queries.
3. Introduce Flyway, baseline the reconciled schema once, and make Hibernate `validate` the only deployment-time schema check.
4. Test every forward migration, application startup, data backfill, and rollback/forward-fix procedure on a production-like copy.
5. Prevent application traffic until migrations and required storage backfills report success.

## Required schema/data changes

| Area | Required migration or verification |
| --- | --- |
| User identity | Add nullable `tbl_user.provider_subject`; enforce uniqueness for `(provider, provider_subject)` when a subject is present. Normalize emails to the canonical lowercase form, resolve collisions manually, then enforce canonical email uniqueness. |
| Admin authorization | Seed the permission rows used by the code, including admin-panel and venue-application management permissions, and assign them through reviewed roles. Provision the first owner/admin through an auditable one-time operational process; never through a production startup seeder. |
| Venue applications | Backfill every missing `neighborhood_id`, verify city/district/neighborhood consistency, then make the relationship non-null. Decide and migrate the rejected-application account lifecycle before exposing reapplication. |
| Studio applications | First deploy a compatibility binary that can read `REJECTED_STUDIO_REQUEST` while rejection writes remain disabled by the reviewed rollout control. Only after every old node is drained, run `scripts/db/2026-07-21-studio-domain.sql` with `psql -v ON_ERROR_STOP=1`, reconcile counts, and verify the deterministic `(status, application_date, id)` and applicant-history indexes. The script widens a legacy bounded `tbl_user.status` text column to `varchar(32)` before backfilling the 23-character rejected state; PostgreSQL enum/check-constraint variants still require explicit live-schema reconciliation. The script backfills the newest rejected registration account; active multi-role applicants are intentionally unchanged. Then enable the new decision/login behavior. |
| Studio domain | Reconcile every table, constraint, permission seed, `btree_gist` dependency, timezone value, reservation/occupancy count, and media reference created by `scripts/db/2026-07-21-studio-domain.sql` against the live PostgreSQL snapshot. The date-named script is not a substitute for the missing Flyway baseline. |
| Direct messages | Detect reversed or duplicate participant pairs, move messages to one winner conversation, canonicalize participant order, then add `CHECK (user_a_id < user_b_id)` and a unique constraint on `(user_a_id, user_b_id)`. |
| Media state | Apply `scripts/db/2026-07-15-media-status-lifecycle.sql` before starting the new binary. It adds exact upload/deletion deadlines and accepts all current durable cleanup/transcode states. Add indexes that support expiry, cleanup, queued/sent recovery, and stale-processing scans after validating them with PostgreSQL query plans. |
| Protected media | Inventory existing `PRIVATE`/`UNLISTED` rows, copy their objects from any public bucket to the private origin, rewrite keys to the protected convention, clear stable public URLs, verify checksums, and only then delete legacy public copies. |
| Public media | Inventory legacy upload/raw-video keys. New uploads use private mutable keys followed by server-only immutable verified keys; do not rewrite legacy rows without a per-status reconciliation plan. |

## Safe rollout order

1. Stop new writes for the affected domain or deploy a compatibility version that can read both old and new representations.
2. Apply additive schema changes and permission seeds. Run the idempotent media migration first, then the reconciled Studio migration with `ON_ERROR_STOP=1`; record row counts and constraint/index verification as release evidence.
3. Run idempotent, checkpointed data/object backfills with counts and checksum evidence.
4. Validate constraints, duplicates, null counts, storage reachability, and application startup with the production profile.
5. Deploy the application version that writes the new representation.
6. After the observation window, apply non-null/check/unique constraints and remove legacy compatibility paths in a later release.

New enum/status values and protected storage keys are not safely rollback-compatible with an older binary that cannot read them. Drain old media writers/workers before the constraint switch, or use a two-phase compatibility rollout. Rollback planning must therefore use a forward-fix or a compatibility binary, not an unconditional old-image redeploy.

The same rule is mandatory for the Studio rejection state: while any old node is live, do not write or backfill `REJECTED_STUDIO_REQUEST`. After the migration, rollback is safe only to a version that can deserialize that enum value; an older binary is not a valid rollback target.

## Explicit product/architecture decisions still open

### Production native-media deployment gate

The software boundary is implemented: production API instances fail closed
with native work disabled, and `soundconnect-media-worker.jar` provides an
explicit no-web, allow-listed worker context with separate DB/Rabbit/S3
identities, bounded native resources, and DB+Rabbit readiness. The API artifact
still rejects every worker profile, so it cannot be repurposed accidentally.

Release remains gated on environment-specific evidence that cannot be proven
by Compose: apply the idempotent column-limited PostgreSQL role after schema
migration, create the consume-only Rabbit user, attach the reviewed S3/KMS
workload policy, and verify the deployed namespace's egress deny policy. Follow
the rollout and acceptance procedure in
`docs/MediaModule/IsolatedMediaWorker.md`; never enable native workers on API
nodes.

- Rejected venue application lifecycle: re-enable and allow reapplication, close the account, or provide a separate appeal flow.
- Rejected Studio registration lifecycle is decided: `PENDING_STUDIO_REQUEST -> ACTIVE` on approval and `-> REJECTED_STUDIO_REQUEST` on rejection. Correct-password login returns stable error code `1112`; OTP verification cannot reactivate the account; the Flutter client routes to the dedicated support/appeal screen. Reapplication remains intentionally unavailable until a separate product decision.
- Studio application API compatibility: the list response root changes from a bare list to `PageResponse`, and rejection moves from a query parameter to a JSON body. The pagination object publishes both `page` and legacy `number`, but that does not make the root-shape or rejection-request changes compatible. If any released client consumes these endpoints, use versioned or explicitly dual transition endpoints and prove both old/new client combinations before rollout; do not rely on a mixed-version deployment. A coordinated pre-launch cutover is acceptable only when no released client depends on the old contract.
- Studio scale/abuse gate: request sizes, search text, page sizes, page depth, and date windows are bounded in code, and tenant-scoped mutations cannot lock another Studio's rows. Production still requires ingress rate limits per account/IP, concurrency limits, and a production-sized PostgreSQL `EXPLAIN (ANALYZE, BUFFERS)`/load run. Contains-search currently uses portable literal matching; if measured latency exceeds the budget, introduce reviewed `pg_trgm`/search-index support rather than removing the literal-query guarantees.
- High-cardinality Studio transitions: approving one request rejects every overlapping pending request, and archiving a room cancels every future request/occupancy. Those sets are intentionally updated atomically but are not yet chunked. Prove a reviewed maximum-cardinality case under the API timeout and DB-lock budget; before unbounded traffic, move per-recipient delivery to the durable outbox and a bounded batch worker so a spammed room cannot create an oversized transaction/event fan-out.
- `spring.jpa.open-in-view`: currently retained as a compatibility bridge. Replace lazy serialization with transactional projections/entity graphs before disabling it.
- Media worker crash recovery: lease/attempt fencing, heartbeat, bounded retry, and an isolated worker target are implemented. Production release still requires the environment-specific identity/network acceptance evidence and an operator-controlled replay path for exhausted transient failures.
- Durable side effects: use an outbox/reconciliation design for mail intent, Studio reservation notifications, media deletion/publication, and CDN invalidation before claiming exactly-once operational behavior. Studio notification listeners run after commit, but `NotificationProducer` currently treats Rabbit publish as best effort and cannot recover a broker outage, return, or negative confirm after the reservation commit. This is a production release blocker, not an acceptable warning.
- Observability: the production profile currently exposes health probes but no reviewed Studio latency/error/saturation/business metrics, tracing, dashboards, or alerts. Before traffic, instrument reservation conflict/error rates, application decision latency, notification outbox lag/failures, room/equipment query latency, DB pool saturation, Rabbit health, and migration reconciliation; prove alert routing with a prod-like failure drill.
- Privacy and recovery: define retention/masking for Studio application phone/address data and reservation phone snapshots, test account-erasure/legal-hold behavior, and complete a timed PostgreSQL/object-storage backup restore plus forward-fix drill before launch.
- Protected HLS delivery: remains fail-closed until authorization covers the manifest and every segment.

Studio code-level stabilization is in scope and its bounded APIs, account state machine, UTC response contract, validation, and migration notes are documented in `docs/StudioModule/Architecture.md`. This does not waive the live-schema, durable-messaging, load, observability, backup/restore, security, or rollout evidence above; production readiness must not be claimed until those external gates pass.
