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

The Android client must create channel `soundconnect_notifications`. The envelope's stable collapse
key (<=64 UTF-8 bytes) is used for the Android notification tag and APNs collapse ID so repeated
attempts can replace the same visible notification. Android's transport `collapse_key` is fixed to
`soundconnect_updates`; per-notification IDs must not create unbounded offline collapse groups
(FCM retains at most four distinct keys per device). FCM notification messages are always collapsible
and may ignore this explicit key. A reconnect can therefore show only the latest generic alert;
the durable inbox and unread reconciliation retain all underlying notifications. A visible notification
uses Android HIGH and APNs alert/10 with bounded expiry. The API currently accepts registration tokens;
FCM's newer FID addressing can be added as an explicit registration kind when the Flutter client uses it.

Official references:
- https://firebase.google.com/docs/cloud-messaging/send/v1-api
- https://firebase.google.com/support/release-notes/admin/java
- https://firebase.google.com/docs/cloud-messaging/error-codes
- https://firebase.google.com/docs/cloud-messaging/customize-messages/collapsible-message-types
- https://github.com/googleapis/google-auth-library-java/blob/main/CHANGELOG.md
