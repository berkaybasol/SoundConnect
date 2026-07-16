# Isolated native media worker

The production API and the FFmpeg/ffprobe workload are separate process and
credential boundaries. The API builds as `soundconnect-api.jar`; native image
and HLS work builds as `soundconnect-media-worker.jar` and runs the no-web
`MediaWorkerApplication` context.

## Enforced boundary

The worker has an explicit Spring allow-list rather than the API component
scan. Its context contains the media asset entity/repository, lease and status
updates, image/HLS processing, S3, one Rabbit listener, and DB/Rabbit readiness.
It has no HTTP server/controllers, security filter chain, authentication,
MailerSend, Redis, WebSocket, API schedulers, Rabbit publisher/template, or
unrelated repositories. `MediaWorkerContextBoundaryTest` makes this an
executable contract.

The reverse boundary is also fail closed. Production API nodes require native
workers off and dispatch on. They reject every `media-worker` profile and any
worker DB, Rabbit, or static S3 credential injected into the API environment.
The dedicated worker rejects API JWT, mail, Google, Spotify, and generic S3
secrets. It also rejects web mode, shared DB/Rabbit identities, unsafe worker
counts, or disabled native capabilities.

The worker JAR uses the repository's common runtime classpath, but bytecode
presence is not authority: the Spring allow-list plus separate DB, Rabbit, S3,
network, and container identities form the capability boundary.

## Build and start

```powershell
.\gradlew.bat bootJar mediaWorkerBootJar
```

The manifests must report these entry points:

- API: `com.berkayb.soundconnect.SoundConnectApplication`
- worker: `com.berkayb.soundconnectworker.MediaWorkerApplication`

`bootRun` and the IntelliJ default remain pinned to the API main class. The
production worker must start only its dedicated artifact with
`SPRING_PROFILES_ACTIVE=prod,media-worker`. It exposes no port.

Local Compose runs the split API and worker with `dev.cmd up`. For the existing
IntelliJ flow, `dev.cmd idea` starts PostgreSQL/RabbitMQ/Redis and stops both
Compose application processes; `SoundConnectApplication` then keeps the local
in-process image/video workers enabled for normal debugging.

## Production identity contract

Provision independent values through the deployment secret manager:

| Capability | Worker setting | Rule |
| --- | --- | --- |
| PostgreSQL | `SOUNDCONNECT_MEDIA_WORKER_POSTGRES_URL`, `_USERNAME`, `_PASSWORD` | Dedicated login only; never the schema owner/API login |
| RabbitMQ | `SOUNDCONNECT_MEDIA_WORKER_RABBITMQ_HOST`, `_PORT`, `_USERNAME`, `_PASSWORD`, `_VIRTUAL_HOST` | Dedicated consume-only user; use TLS port 5671 in production |
| Rabbit TLS | `_RABBITMQ_SSL_ENABLED`, optional worker-only trust/key-store settings | `true` and hostname verification in production |
| S3 | `_S3_PUBLIC_BUCKET`, `_S3_PRIVATE_BUCKET`, `_AWS_REGION`, `_CDN_BASE_URL` | Buckets must be distinct; CDN URL is explicit in production |
| AWS auth | workload/task role, or `_S3_ACCESS_KEY` + `_S3_SECRET_KEY` | Workload identity is preferred; static pair must be worker-only |

Never inject JWT, MailerSend, Google, Spotify, generic AWS/S3 static
credentials, API/shared `SOUNDCONNECT_POSTGRES_*` (including legacy
`POSTGRE_*`), `SPRING_DATASOURCE_*` credentials, API Rabbit
`SPRING_RABBITMQ_*` credentials/addresses/vhost/TLS stores, or any Redis
URL/username/password into the worker. Harmless shared host/port hints are not
rejected, but the effective worker DB/Rabbit identities must still resolve from
the worker namespace.

## PostgreSQL grant

Run `docker/postgres/configure-media-worker-role.sh` after the versioned schema
migration and before starting the worker. It is idempotent and grants:

- connect plus `public` schema usage, without schema create;
- `SELECT` on `tbl_media_asset` only;
- column-level `UPDATE` only for durable media lifecycle, lease, attempt,
  playback/thumbnail, and derived metadata columns;
- no insert/delete and no update authority over identity, ownership, source
  key, visibility, or unrelated tables.

`MediaWorkerDatabaseRolePostgresTest` executes the actual grant script twice
against PostgreSQL and proves allowed updates succeed while source-key update,
insert, and delete fail with permission denied.

## RabbitMQ grant

Run `docker/rabbitmq/configure-media-worker-user.sh` using a short-lived broker
administrator credential, then remove that administrator credential from the
job. The worker user gets exact configure/read permission for
`media.transcode.video.hls`, `write=^$`, and no management tag. The worker
context intentionally has no `RabbitTemplate` or `RabbitAdmin`; API nodes own
topology declaration and publishing.

## Least-privilege S3 policy

Use the real bucket ARNs and retain legacy source patterns only during a
measured migration window. The worker requires no bucket listing, presigning,
CloudFront, ACL, bucket-policy, or bucket-administration permission.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadVerifiedVideoSources",
      "Effect": "Allow",
      "Action": "s3:GetObject",
      "Resource": [
        "arn:aws:s3:::PRIVATE_BUCKET/verified/media/*/attempts/*/source.*",
        "arn:aws:s3:::PRIVATE_BUCKET/verified/media/*/source.*"
      ]
    },
    {
      "Sid": "ReadPublicImageSources",
      "Effect": "Allow",
      "Action": "s3:GetObject",
      "Resource": [
        "arn:aws:s3:::PUBLIC_BUCKET/media/*/attempts/*/source.*",
        "arn:aws:s3:::PUBLIC_BUCKET/media/*/source.*"
      ]
    },
    {
      "Sid": "WriteGeneratedMediaOnly",
      "Effect": "Allow",
      "Action": "s3:PutObject",
      "Resource": [
        "arn:aws:s3:::PUBLIC_BUCKET/media/*/hls/*",
        "arn:aws:s3:::PUBLIC_BUCKET/media/*/attempts/*/thumbnail.jpg",
        "arn:aws:s3:::PUBLIC_BUCKET/media/*/thumbnail.jpg"
      ]
    },
    {
      "Sid": "CompensateUnattachedImageVariant",
      "Effect": "Allow",
      "Action": "s3:DeleteObject",
      "Resource": [
        "arn:aws:s3:::PUBLIC_BUCKET/media/*/attempts/*/thumbnail.jpg",
        "arn:aws:s3:::PUBLIC_BUCKET/media/*/thumbnail.jpg"
      ]
    }
  ]
}
```

If SSE-KMS is enabled, add only the corresponding key's decrypt permission for
source reads and encrypt/data-key permission for generated public objects.
Constrain the KMS grant with S3 encryption-context and ViaService conditions.

## Runtime and health

Only the `media-worker` image target contains FFmpeg. It runs non-root with a
read-only root filesystem, all Linux capabilities dropped,
`no-new-privileges`, a PID limit, CPU/memory limits, and a 6 GiB `noexec`
`/tmp` tmpfs. Listener and native worker concurrency are fixed at one so one
6 GiB reservation cannot overcommit the volume.

There is no HTTP/JMX actuator surface. At startup and every 15 seconds the
worker validates a real JDBC connection and a real Rabbit connection, closes
the health probe handles, and atomically writes the successful probe epoch to
`/tmp/soundconnect-media-worker.ready`. The container health command rejects a
missing, malformed, future, or older-than-60-seconds marker; existence alone is
never readiness. Process exit remains the liveness signal.

Local Compose supplies the same existing CloudFront fallback as the normal
local API and keeps `aclPublicReadOnPut=false`. This makes generated URLs usable
without making objects public by ACL. Production has no implicit worker
fallback contract: startup requires an explicit CDN URL, OAC-style ACL=false,
and Rabbit TLS=true. The production API enforces the same explicit CDN and
ACL=false delivery contract.

## Network policy and rollout gate

Compose cannot enforce a production egress allow-list. Before release, the
orchestrator/VPC policy must prove that the worker can reach only:

- the worker PostgreSQL endpoint on 5432;
- the worker RabbitMQ endpoint on TLS 5671;
- the regional S3 endpoint (and STS only when the workload identity requires
  it), plus the platform DNS/CA/telemetry endpoints explicitly approved by
  operations.

Deny API ingress and all other egress. Verify the denial from the deployed
worker namespace, not from a developer machine.

Roll out the API first with dispatch enabled and native workers disabled. Apply
schema migration, then DB/Rabbit grants and IAM/network policy, start one
worker, confirm readiness and queue drain, and scale gradually. On rollback,
stop/drain the worker before revoking its identities; queued rows remain the
durable source of truth.

## Verification

```powershell
.\gradlew.bat test --tests "com.berkayb.soundconnectworker.*" `
  --tests "com.berkayb.soundconnect.shared.config.ProductionSafetyValidatorTest"
.\gradlew.bat bootJar mediaWorkerBootJar
.\dev.cmd config
powershell -File scripts/verify-media-worker-compose.ps1
```

`dev.cmd` creates the ignored `.env.worker-db.local` and
`.env.worker-rabbit.local` files with independent random credentials before it
renders Compose. `.env.local` is API-only and must not contain worker-prefixed
variables.

Production evidence must additionally include IAM policy simulation, a denied
Rabbit publish attempt, denied DB insert/delete/source-key update attempts, a
denied non-approved egress attempt, and successful DB+Rabbit readiness.
