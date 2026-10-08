# FCM transport

This adapter uses the official FCM HTTP v1 API and Google Auth Java 1.43.0. Firebase Admin Java
9.10.0 was evaluated: its public messaging configuration does not expose retry disabling and its
default HTTP/2 transport does not cancel the response future on interruption. Using HTTP v1 keeps
retry scheduling in the durable push job rather than introducing a second hidden send loop.

All beans are conditional on `app.notification.push.enabled=true`. Disabled applications require
no Firebase project or credentials. Enabled configuration requires `app.notification.push.fcm.project-id`.
Use Application Default Credentials by default, or configure `credentials-path` to an external
service-account JSON file. No private credential belongs in source control or application YAML.

The defaults are connect 3s, read/request 5s, total 8s, maximum 2 concurrent requests and no executor
queue. Each timeout is validated within 100ms–10s; connect/read cannot exceed total. OAuth HTTP calls
share the send deadline. Supported credential retries are disabled and the transport refuses further
network calls after interruption/deadline. A bounded worker future also covers credential waiting.
Cancel requests are best effort: an already accepted remote request cannot be recalled; consequently
timeout is an ambiguous outcome. Notification IDs/collapse IDs reduce duplicate display, but this is
not exactly-once delivery. Dispatcher leases must exceed the maximum total timeout with margin.

Each invocation makes one FCM HTTP submission, with no application retries or redirect following.
`ACCEPTED` only records a syntactically valid provider acceptance ID, not device receipt, display, or
user read. Only explicit FCM `UNREGISTERED` invalidates the device registration. `INVALID_ARGUMENT`
can indicate an invalid payload and must not delete a working device. Retry-After is parsed for
seconds and HTTP dates; quota errors get a minimum 60-second delay. Arbitrary response content,
tokens, credentials and message bodies are never logged or included in safe error codes.

Supported Android native presentations use data-only HTTP v1 messages with no `android.collapse_key`,
`notification`, `android.notification` or APNs payload. Each event is submitted separately with HIGH
priority and its remaining TTL; retries do not extend expiry. FCM can hold up to 100 pending
non-collapsible messages per Android device; overflow can discard the pending messages. Delivery
order, TTL and OS/network/permission conditions still limit delivery. The configured default lifetime
is 24 hours (not FCM's general default). Provider ACCEPTED is not device receipt or read.

Native exact notificationId child tags, recipient/epoch groups and dedup remain independent of FCM
queue behavior. Summaries count actual current children, not server unread rows. Resume reconciliation
only dismisses existing cards; missed, expired or swiped cards are not backfilled, and ACCEPTED jobs
are not replayed. The inbox keeps its own visibility/read/delete rules.

The Android client must create channel `soundconnect_notifications`. For the generic fallback only,
the envelope's stable collapse key (<=64 UTF-8 bytes) supplies the Android notification tag and APNs
collapse ID. Its Android transport `collapse_key` remains `soundconnect_updates`; generic notification
messages are always collapsible and FCM may ignore that explicit key. A reconnect may therefore show
only the latest generic alert. Generic delivery retains Android HIGH and APNs alert/10 with bounded
expiry. The API currently accepts registration tokens;
FCM's newer FID addressing can be added as an explicit registration kind when the Flutter client uses it.

Official references:
- https://firebase.google.com/docs/cloud-messaging/send/v1-api
- https://firebase.google.com/support/release-notes/admin/java
- https://firebase.google.com/docs/cloud-messaging/error-codes
- https://firebase.google.com/docs/cloud-messaging/customize-messages/collapsible-message-types
- https://firebase.google.com/docs/cloud-messaging/customize-messages/setting-message-lifespan
- https://github.com/googleapis/google-auth-library-java/blob/main/CHANGELOG.md
