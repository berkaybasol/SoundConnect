package com.berkayb.soundconnect.modules.tablegroup.notification.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "tbl_table_group_notification_outbox", indexes = {
		@Index(name = "idx_tg_notification_outbox_due", columnList = "status,next_attempt_at,created_at"),
		@Index(name = "idx_tg_notification_outbox_lease", columnList = "status,lease_until"),
		@Index(name = "idx_tg_notification_outbox_created", columnList = "created_at")
})
public class TableGroupNotificationOutbox {

	@Id
	@Column(name = "event_id", nullable = false, updatable = false, columnDefinition = "uuid")
	private UUID eventId;

	@Column(name = "recipient_id", nullable = false, updatable = false, columnDefinition = "uuid")
	private UUID recipientId;

	@Enumerated(EnumType.STRING)
	@Column(name = "notification_type", nullable = false, updatable = false, length = 64)
	private NotificationType notificationType;

	@Column(name = "title", nullable = false, updatable = false, length = 160)
	private String title;

	@Column(name = "message", nullable = false, updatable = false, length = 1000)
	private String message;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
	private Map<String, Object> payload;

	@Column(name = "email_force", nullable = false, updatable = false)
	private boolean emailForce;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 24)
	private TableGroupNotificationOutboxStatus status;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at", nullable = false)
	private Instant nextAttemptAt;

	@Column(name = "lease_owner", length = 100)
	private String leaseOwner;

	@Column(name = "lease_until")
	private Instant leaseUntil;

	@Column(name = "last_error_type", length = 200)
	private String lastErrorType;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	static TableGroupNotificationOutbox pending(NotificationInboundEvent event, Instant now) {
		if (event.eventId() == null || event.recipientId() == null || event.type() == null
				|| !"TABLE".equals(event.type().getCategory()) || event.title() == null
				|| event.title().isBlank() || event.message() == null || event.message().isBlank()
				|| event.payload() == null || event.occurredAt() == null) {
			throw new IllegalArgumentException("Invalid TableGroup notification event");
		}
		return TableGroupNotificationOutbox.builder()
				.eventId(event.eventId())
				.recipientId(event.recipientId())
				.notificationType(event.type())
				.title(event.title().strip())
				.message(event.message().strip())
				.payload(new LinkedHashMap<>(event.payload()))
				.emailForce(Boolean.TRUE.equals(event.emailForce()))
				.occurredAt(event.occurredAt())
				.status(TableGroupNotificationOutboxStatus.PENDING)
				.attemptCount(0)
				.nextAttemptAt(now)
				.createdAt(now)
				.updatedAt(now)
				.build();
	}
}
