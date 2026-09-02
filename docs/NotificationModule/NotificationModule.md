# Full Stack Notification Module (RabbitMQ + Redis + WebSocket + MailerSend)

## Publisher confirm timeout

Durable notification outboxes share the central `NotificationProducer`, so its
RabbitMQ confirm timeout is configured once with
`app.messaging.notification.publisher-confirm-timeout`. Deployments should set
`SOUNDCONNECT_NOTIFICATION_PUBLISHER_CONFIRM_TIMEOUT` to a duration between
`1s` and `30s`; the default is `5s`.

Both `app.notification.collab-outbox.lease-duration` and
`app.notification.table-group-outbox.lease-duration` must be at least the
shared confirm timeout plus `1s`. Startup fails when either lease is shorter,
preventing a second node from reclaiming an event while the first publisher is
still waiting for RabbitMQ confirmation.

The first production contract exposes only the neutral shared environment
variable. Module-specific aliases are intentionally not accepted.
