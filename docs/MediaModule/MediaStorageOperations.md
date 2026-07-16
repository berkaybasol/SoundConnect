# Media storage deletion and cache operations

## Runtime guarantees

- A media delete is first persisted as `DELETION_PENDING`; S3 and CloudFront are
  touched only after the database transaction commits.
- The worker repeats the first-party reference check immediately before object
  I/O. A referenced asset is retained and retried without deleting any bytes.
- Mutable `quarantine/` and `protected/` uploads are not finally removed until
  their exact persisted presigned-PUT expiry, clock-skew allowance, and bounded
  in-flight transfer-completion grace have all elapsed. S3 evaluates URL expiry
  when a request starts, so expiry alone is not a completion fence. The runtime
  enforces a non-reducible 24-hour minimum completion grace (configurable up to
  seven days) and retains the durable cleanup row through the final sweep.
- A successful private/unlisted completion intentionally leaves its physical
  mutable `media/` source in place until that safety window closes. A dedicated,
  bounded executor rotates over all committed `READY` `private-verified/` rows,
  derives the exact mutable predecessor, and deletes it idempotently. The cursor
  advances past failures and resets after a complete sweep, so one unavailable
  object cannot starve later assets and process restarts recover from the rows.
- Cleanup removes all deterministic crash-stage objects (mutable upload,
  immutable verification snapshot, and a possible public promotion) before the
  durable cleanup row is finalized.
- Deleting a live `private-verified/` asset also derives and deletes its mutable
  `media/` predecessor; this covers deletion before the recovery sweep arrives.
- Failed HLS work first persists `HLS_CLEANUP`. The Rabbit listener never walks
  or deletes a remote tree. A bounded worker batch-deletes the private verified
  source, its deterministic mutable companions, and the public HLS prefix; the
  row becomes terminal only after the upload completion fence closes.

## Public CDN deletion

Set `S3_CLOUDFRONT_DISTRIBUTION_ID` in production to enable deletion invalidation.
Every deletion submits exactly one path:

```text
/media/{asset UUID}/*
```

The runtime role needs only `cloudfront:CreateInvalidation` on the configured
distribution ARN. Do not grant wildcard CloudFront administration permissions.
Example policy shape:

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": "cloudfront:CreateInvalidation",
    "Resource": "arn:aws:cloudfront::<account-id>:distribution/<distribution-id>"
  }]
}
```

Local development does not require a distribution id. As a fail-safe, all new
public objects are centrally capped at 5 minutes browser cache and 1 hour shared
CDN cache, including HLS segments. CloudFront invalidation is still recommended
for prompt deletion and for objects uploaded before this bounded policy existed.

## Private bucket lifecycle backstop

The private bucket lifecycle must expire only this physical prefix:

- `quarantine/`: mutable public-intent uploads, including late PUTs.

After the mandatory legacy migration described below, it may additionally
expire this physical prefix:

- `media/`: mutable private/unlisted upload sources only (2-day backstop).

Never apply a broad expiry to `verified/` or `private-verified/`. Public video
assets retain their verified source while HLS is live, and `private-verified/`
contains live private or unlisted media. Crash-orphan verification snapshots are
removed by the durable deterministic cleanup worker instead.

### Mandatory legacy migration before enabling `media/` expiry

Older SoundConnect releases stored live private/unlisted READY objects directly
under the private bucket's physical `media/` prefix. Enabling expiry before
migrating those objects would permanently delete live media. Before enabling:

1. Inventory every READY private/unlisted row whose logical `storageKey` is not
   `protected/private-verified/...`.
2. Copy and integrity-check each corresponding private-bucket object into
   physical `private-verified/media/...`.
3. Atomically update the row to logical
   `protected/private-verified/media/...`, then verify owner access.
4. Back up the inventory and confirm the legacy READY count is zero.

The template therefore ships the `media/` rule **Disabled**. Previewing or
applying the quarantine rule cannot enable it accidentally. Only after completing
and verifying the migration, preview the protected backstop with both explicit
switches:

```powershell
.\scripts\configure-private-media-lifecycle.ps1 `
  -EnableProtectedUploadExpiry `
  -ConfirmLegacyProtectedMediaMigrated
```

Then apply the reviewed merged configuration:

```powershell
.\scripts\configure-private-media-lifecycle.ps1 `
  -EnableProtectedUploadExpiry `
  -ConfirmLegacyProtectedMediaMigrated `
  -Apply
```

Preview the merged lifecycle without changing AWS:

```powershell
.\scripts\configure-private-media-lifecycle.ps1
```

Apply it after reviewing the output:

```powershell
.\scripts\configure-private-media-lifecycle.ps1 -Apply
```

The script preserves lifecycle rules it does not own and replaces only the
`soundconnect-` rules defined in `scripts/private-media-lifecycle.json`. Once the
protected rule has been explicitly enabled, later ordinary runs preserve its
enabled state instead of silently disabling it.

## Database lifecycle migration

Before a build containing durable media cleanup states is started, apply the
idempotent PostgreSQL migration:

```powershell
Get-Content .\scripts\db\2026-07-15-media-status-lifecycle.sql -Raw |
  docker exec -i soundconnect-local-postgres-1 psql -v ON_ERROR_STOP=1 -U postgres -d soundconnectdb
```

The migration takes an exclusive table lock and applies its schema changes,
constraint replacement, backfills, and indexes in one transaction. The
`ON_ERROR_STOP` flag makes an aborted transaction visible to automation instead
of allowing `psql` to continue after the first error. In staging or production,
drain old media writers/workers first or deploy a compatibility binary; an old
binary cannot safely interpret the new status values.
