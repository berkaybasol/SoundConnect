package com.berkayb.soundconnect.modules.event.performer.outbox;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
		name = "tbl_event_performer_notification_outbox",
		indexes = {
				@Index(
						name = "idx_event_performer_notification_outbox_due",
						columnList = "status,next_attempt_at,created_at"
				),
				@Index(
						name = "idx_event_performer_notification_outbox_lease",
						columnList = "status,lease_until"
				),
				@Index(
						name = "idx_event_performer_notification_outbox_created",
						columnList = "created_at"
				),
				@Index(
						name = "idx_event_performer_notification_outbox_published",
						columnList = "status,published_at"
				)
		}
)
public class EventPerformerNotificationOutbox {

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
	private EventPerformerNotificationOutboxStatus status;

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

	public static EventPerformerNotificationOutbox pending(
			NotificationInboundEvent event,
			Instant now
	) {
		Objects.requireNonNull(event, "event is required");
		Objects.requireNonNull(now, "now is required");
		validateText(event.title(), 160, "event.title");
		validateText(event.message(), 1000, "event.message");

		return EventPerformerNotificationOutbox.builder()
				.eventId(Objects.requireNonNull(event.eventId(), "event.eventId is required"))
				.recipientId(Objects.requireNonNull(event.recipientId(), "event.recipientId is required"))
				.notificationType(Objects.requireNonNull(event.type(), "event.type is required"))
				.title(event.title())
				.message(event.message())
				.payload(new LinkedHashMap<>(Objects.requireNonNull(event.payload(), "event.payload is required")))
				.emailForce(Boolean.TRUE.equals(event.emailForce()))
				.occurredAt(Objects.requireNonNull(event.occurredAt(), "event.occurredAt is required"))
				.status(EventPerformerNotificationOutboxStatus.PENDING)
				.attemptCount(0)
				.nextAttemptAt(now)
				.createdAt(now)
				.updatedAt(now)
				.build();
	}

	private static void validateText(String value, int maximumLength, String field) {
		if (value == null || value.isBlank() || value.length() > maximumLength) {
			throw new IllegalArgumentException(field + " must contain 1.." + maximumLength + " characters");
		}
	}
}
