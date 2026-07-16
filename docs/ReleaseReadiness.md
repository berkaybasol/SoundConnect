# Release readiness and database migration gate

This repository is suitable for local feature development only after the automated quality gates pass. A production or staging deployment remains blocked until the database and object-storage migrations below are implemented against an authorized snapshot of the real environment.

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
| Direct messages | Detect reversed or duplicate participant pairs, move messages to one winner conversation, canonicalize participant order, then add `CHECK (user_a_id < user_b_id)` and a unique constraint on `(user_a_id, user_b_id)`. |
| Media state | Apply `scripts/db/2026-07-15-media-status-lifecycle.sql` before starting the new binary. It adds exact upload/deletion deadlines and accepts all current durable cleanup/transcode states. Add indexes that support expiry, cleanup, queued/sent recovery, and stale-processing scans after validating them with PostgreSQL query plans. |
| Protected media | Inventory existing `PRIVATE`/`UNLISTED` rows, copy their objects from any public bucket to the private origin, rewrite keys to the protected convention, clear stable public URLs, verify checksums, and only then delete legacy public copies. |
| Public media | Inventory legacy upload/raw-video keys. New uploads use private mutable keys followed by server-only immutable verified keys; do not rewrite legacy rows without a per-status reconciliation plan. |

## Safe rollout order

1. Stop new writes for the affected domain or deploy a compatibility version that can read both old and new representations.
2. Apply additive schema changes and permission seeds. For this media rollout,
   run the idempotent `scripts/db/2026-07-15-media-status-lifecycle.sql` first.
3. Run idempotent, checkpointed data/object backfills with counts and checksum evidence.
4. Validate constraints, duplicates, null counts, storage reachability, and application startup with the production profile.
5. Deploy the application version that writes the new representation.
6. After the observation window, apply non-null/check/unique constraints and remove legacy compatibility paths in a later release.

New enum/status values and protected storage keys are not safely rollback-compatible with an older binary that cannot read them. Drain old media writers/workers before the constraint switch, or use a two-phase compatibility rollout. Rollback planning must therefore use a forward-fix or a compatibility binary, not an unconditional old-image redeploy.

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
- `spring.jpa.open-in-view`: currently retained as a compatibility bridge. Replace lazy serialization with transactional projections/entity graphs before disabling it.
- Media worker crash recovery: lease/attempt fencing, heartbeat, bounded retry, and an isolated worker target are implemented. Production release still requires the environment-specific identity/network acceptance evidence and an operator-controlled replay path for exhausted transient failures.
- Durable side effects: use an outbox/reconciliation design for mail intent, media deletion/publication, and CDN invalidation before claiming exactly-once operational behavior.
- Protected HLS delivery: remains fail-closed until authorization covers the manifest and every segment.

The Studio domain is intentionally outside this stabilization scope and must be reviewed and refactored with its product contract before its schema or API is changed.
