# Notification consumer DLQ: observation and selected recovery

The consumer DLQ is separate from outbox attempt budgets and push delivery
DEAD_LETTER jobs. An outbox PUBLISHED row proves ingress broker acceptance; it
does not prove a committed receipt, inbox row, push delivery or user read.

## Access and defaults

Use the existing signed JWT and DB-backed roles: OWNER or ADMIN, with no LISTENER
role. A mixed LISTENER+ADMIN account is rejected. An incomplete listener profile
may first encounter the existing onboarding gate. Push enablement is unrelated.
No endpoint accepts a broker address, queue, vhost, exchange, routing key or new
message body. The existing notification topology and Rabbit connection settings
supply them. Do not use the shared product environment for fault tests.

`app.messaging.notification.dlq-ops` defaults and validated ranges:

| Setting | Default | Range |
| --- | ---: | --- |
| replay-enabled | false | boolean |
| window | 10 | 1–25 manual deliveries per command |
| max-body-bytes | 65536 | 1024–262144 |
| max-window-bytes | 262144 | 1024–1048576 |
| rpc-timeout-ms | 2000 | 100–5000 |
| deadline-ms | 10000 | 1000–30000; greater than RPC timeout |
| interval-ms | 30000 | 1000–300000 |
| stale-ms | 90000 | 2000–600000; greater than interval |

Replay can be enabled with `SOUNDCONNECT_NOTIFICATION_DLQ_REPLAY_ENABLED=true`
at a controlled restart of the intended deployment. Disable it after recovery.
Configuration changes are deployment actions, not operations API parameters.
Observation and explicit inspection remain available with replay disabled.

One observer worker and one operator worker have **zero queued tasks** per
instance. Concurrent operator requests receive `BUSY_OR_STOPPED`. Each attempt
uses a dedicated connection, no auto recovery, bounded connect/handshake/RPC,
and a deadline that aborts its connection. Pending manual deliveries requeue on
connection close, including cancellation and shutdown. A stuck worker cannot
spawn replacement workers or an unbounded backlog. The HTTP wait is bounded by
deadline + one RPC timeout; a disconnected HTTP client does not retract an
already started command, which still finishes or hits its deadline.

## Observe without delivery

`GET /api/v1/admin/notifications/dlq/summary` returns a cached passive
`queueDeclarePassive` measurement. The scheduled observer opens no consumer,
does no basic.get, ACK/NACK, conversion, purge or replay. Neither summary nor
health performs content inspection. No manual inspection is a dry run.

Example `data` (values are illustrative):

```json
{"status":"DEGRADED","readyMessages":2,"unackedMessages":null,"totalMessages":null,
 "measuredAt":"2026-10-04T17:00:00Z","stale":false,
 "oldest":{"seconds":null,"source":"UNKNOWN","coverage":"NONE",
 "reason":"PASSIVE_QUEUE_INFO_HAS_NO_AGE","measuredAt":"2026-10-04T17:00:00Z"},
 "replayEnabled":false,"window":10}
```

Ready count excludes unacked deliveries (including another operator's window).
It is not total depth. UP means a fresh passive ready count of zero only. Missing
queue/broker errors give UNAVAILABLE; initial/stale observations give UNKNOWN
and null counts. `measuredAt` is completion time of the last observation attempt;
it is not broker event time. Metrics `notification.dlq.ready` (NaN if unknown),
`notification.dlq.available` and operation action/outcome counters have no
event/recipient/body labels. State transitions and operations use safe logs.

Global oldest age is always unknown. No producer timestamp or `occurredAt` is
mislabelled as DLQ residence time. Manual inspection may show seconds since the
matching **source ingress queue + rejected reason** `x-death.time`. Rabbit 3.13
compresses death entries by queue/reason and this is the first death time, not
the latest residence start after repeated recovery. Coverage is THIS_MESSAGE_ONLY,
never global oldest. Missing, invalid or future matching data gives unknown.

Public health retains `show-details: never`. UNKNOWN/UNAVAILABLE is not healthy;
nonempty is DEGRADED. **HTTP 200 alone is not a PASS**: existing health mapping
allows DEGRADED 200. Inspect status, staleness and the private summary.

## Inspect, select, recover

1. Investigate and fix the root consumer/policy/dependency failure first. Preserve
   evidence and check current account/source eligibility. Never reset outbox
   attempts, push jobs, read state or receipt rows to make replay succeed.
2. Explicitly `POST /api/v1/admin/notifications/dlq/inspect` with **no body**.
   This takes up to the configured window of manual unacked deliveries, returns
   only event UUID, scoped SHA256 fingerprint, enum type, byte count,
   replayability/reason and sample age, then closes its connection to requeue.
   **Order/redelivery flags may change.** It is not non-destructive arbitrary-ID
   lookup. Messages outside the bounded window are not scanned.
3. Choose exactly one replayable record. With replay enabled, POST the exact
   selection to `/api/v1/admin/notifications/dlq/replay`:

   ```json
   {"eventId":"11111111-1111-1111-1111-111111111111","fingerprint":"<64 lowercase hex characters from inspection>"}
   ```

   API request bytes are capped at 2048; duplicate/trailing/unknown fields are
   rejected. The fingerprint binds raw body, configured vhost + DLQ, content
   type/encoding, schema and type headers. Delivery tags never leave the channel.
   The message is revalidated after selection. STALE or absent selection returns
   NOT_FOUND_IN_WINDOW, not a claim of absence from the entire queue.
4. Inspect the returned `operationId`, `outcome`, `brokerAcceptance`, `sourceAck`
   and `consumerOutcome`. Successful transport returns REPLAYED,
   CONFIRMED_ROUTED, ACK_PROCESSED and **NOT_OBSERVED**, respectively. Separately
   check consumer logs/receipt/inbox and the intended recipient's normal exact
   GET/list/count. Suppressed or deduplicated events legitimately create no new
   inbox row. Read and deleted inbox history are protected by permanent receipts.

Example PowerShell commands (use a token obtained through your approved login
flow; do not print or persist it in command transcripts):

```powershell
$opsHeaders = @{ Authorization = "Bearer $env:SOUNDCONNECT_OPS_TOKEN" }
$opsUrl = 'http://127.0.0.1:<intended-port>/api/v1/admin/notifications/dlq'
Invoke-RestMethod "$opsUrl/summary" -Headers $opsHeaders
$inspection = Invoke-RestMethod "$opsUrl/inspect" -Method Post -Headers $opsHeaders
# Choose one exact record from $inspection.data.messages after review.
$selection = @{ eventId = '<selected UUID>'; fingerprint = '<selected fingerprint>' } | ConvertTo-Json
Invoke-RestMethod "$opsUrl/replay" -Method Post -Headers $opsHeaders -ContentType 'application/json' -Body $selection
```

Selected raw body bytes (eventId, recipient, type, occurredAt and payload) are
published unchanged to normal ingress. Only fixed type/schema/identity headers,
bounded broker death history and optional producer timestamp are forwarded;
arbitrary headers, CC/BCC and expiration are omitted. Missing/malformed identity,
unknown schemas, nested/oversized JSON or incompatible headers are never repaired.
They remain in DLQ. The fixed parser never loads arbitrary AMQP type classes.
Messages exceeding the transport cap (body limit + 1 byte) cause connection
closure; their metadata cannot be inspected and the result is unavailable.
The byte window is tested after each bounded delivery; maximum transient body
allocation is one transport-capped body, outstanding tags never exceed window.

Publication is persistent, mandatory, confirmed and checked for returns before
the **single selected tag** is ACKed (`multiple=false`). An ordered passive RPC
after ACK checks that preceding channel frames were processed. Other held messages
requeue on close. NACK/return/timeout/connection loss before ACK retain the source.
There is no automatic loop over recovered messages or background replay.

## Ambiguity, audit and rollback

PUBLISH_AMBIGUOUS or SOURCE_ACK_AMBIGUOUS means a retry can duplicate ingress.
UNAVAILABLE_OR_AMBIGUOUS may have unknown broker/ACK state; do not assume nothing
happened. Check the operation log, fresh summary, explicit inspection and the
durable receipt/inbox outcome before choosing another replay. Network failure
after confirm but before source ACK can leave both a published copy and source.
This is **at-least-once**, not exactly-once transportation. Concurrent instances
hold distinct manual delivery tags and revalidate exact identity/fingerprint;
they never ACK a sibling merely because an in-process lock was acquired.

Safe logs record actor UUID, generated operation UUID, action, STARTED and final
outcome, bounded examined count and broker/ACK state. Failed authorization uses
existing security handling and performs no DLQ operation. Logs are operational
audit, not a new durable command ledger; process loss after STARTED may have no
final log and requires the same ambiguity checks. No token, body or raw exception
detail is logged by the DLQ components. Ensure normal log retention/access controls.

For rollback disable replay in configuration and roll back the intended candidate
if necessary. Do not purge, delete/redeclare the queue, change queue type/policies,
reset receipts or remove user data. This feature adds no schema migration.

## Broker boundary

Source deployment uses RabbitMQ **3.13.7**, and the dedicated BIL-005 fixture uses
that exact local image. Default queues are classic, durable, with no new queue
type/policy migration. A durable DLQ does not make preceding classic automatic
DLX forwarding lossless under every broker failure. Recovery covers messages
already retained in DLQ, not failures before they reach it.

Official contracts: [RabbitMQ 3.13 acknowledgements and confirms](https://www.rabbitmq.com/docs/3.13/confirms),
[3.13 death metadata and forwarding safety](https://www.rabbitmq.com/docs/3.13/dlx).
The implementation uses AMQP manual delivery, not HTTP management `/get`.

## Manual acceptance boundary

Validate on owned disposable PostgreSQL/Rabbit/Redis and localhost HTTP with the
normal JWT filter, DB roles, EVENT plan controller, outbox and consumer. Test mail
and websocket are doubles; FCM is disabled. Login/OTP is not claimed. Physical
Vivo/FCM/new visual approval is UYGULANAMAZ for this backend-only operations change;
mobile payload/navigation/read contracts are unchanged. See the BIL-005 handoff
for exact commands, versions, invocation counts, raw XML/logs and candidate hash.
