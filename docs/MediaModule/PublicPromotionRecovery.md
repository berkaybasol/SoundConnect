# Public promotion recovery

Public `IMAGE` and `AUDIO` uploads first land in the private bucket under
`quarantine/`. After validation the backend creates a server-owned
`verified/` snapshot, promotes that exact version to the public key, and
commits the `READY` row.

Object storage is not part of the database transaction. If the process stops
or an IAM/storage delete fails after commit, the live public object is valid
but either private companion may remain. `MediaPublicPromotionRecoveryScheduler`
uses committed `READY + PUBLIC + PROGRESSIVE + IMAGE/AUDIO` rows as durable
recovery intent and repeatedly deletes only these deterministic companions:

- `verified/{publicKey}` immediately; clients never receive PUT authority for it.
- `quarantine/{publicKey}` only after the exact presigned PUT lifetime,
  clock-skew allowance, and the bounded in-flight transfer-completion grace have
  expired. URL expiry is checked when a request starts, not when it finishes.

The live `{publicKey}` is never passed to deletion. Deletes are idempotent, the
executor and each pass are bounded, duplicate in-flight work is suppressed,
and the rotating cursor wraps after a full sweep so one failing object cannot
starve later rows. A restart only resets the cursor; the unchanged `READY` rows
retain every recovery target.

Transaction `afterCommit` callbacks perform no S3 calls. This keeps storage
timeouts off request threads; the durable sweep owns committed companion
cleanup instead.

Configuration prefix: `media.public-promotion-recovery`. Defaults are enabled,
100 rows per pass, 2 workers, queue capacity 200, 10-minute initial delay and
15-minute fixed delay.
