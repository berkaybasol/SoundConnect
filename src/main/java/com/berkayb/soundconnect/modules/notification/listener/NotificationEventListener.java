package com.berkayb.soundconnect.modules.notification.listener;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.mail.producer.MailProducer;
import com.berkayb.soundconnect.shared.mail.dto.MailSendRequest;
import com.berkayb.soundconnect.shared.mail.enums.MailKind;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;

/**
 * RabbitMQ'dan gelen NotificationInboundEvent mesajlarini tuketir.
 * - Validate yapar
 * - DB'ye kaydeder
 * - Unread cache gunceller
 * - WebSocket push (DTO)
 * - Mail (emailForce / type.emailRecommended)
 * - TODO ElasticSearch indexleme
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationEventListener {
	private static final int MAX_TITLE_LENGTH = 160;
	private static final int MAX_MESSAGE_LENGTH = 1000;

	private final NotificationRepository notificationRepository;
	private final NotificationBadgeCacheHelper badgeCacheHelper;
	private final NotificationMapper notificationMapper;
	private final NotificationWebSocketService notificationWebSocketService;
	private final MailProducer mailProducer;
	private final NotificationService notificationService;
	private final NotificationReceiptRepository receiptRepository;
	
	
	// RabbitMQ'dan notification queue'undan mesajlari dinler. her gelen event icin bu method cagrilir
	@Transactional
	@RabbitListener(queues = "${app.messaging.notification.queue:notification.queue}")
	public void handle (NotificationInboundEvent event) {
		// event dogrulamasini yap. eksik veya hatali ise isleme alma log bas ve cik
		try {
			validate(event);
		} catch (IllegalArgumentException e) {
			log.warn(
					"Invalid NotificationInboundEvent, skipping. eventId={}, type={}, reason={}",
					event == null ? null : event.eventId(),
					event == null ? null : event.type(),
					e.getMessage()
			);
			return;
		}

		if (receiptRepository.claim(event.eventId(), event.recipientId()) == 0 ||
				notificationRepository.existsBySourceEventId(event.eventId())) {
			log.debug(
					"Duplicate NotificationInboundEvent skipped. eventId={}, type={}",
					event.eventId(), event.type()
			);
			return;
		}

		Instant occurredAt = event.occurredAt();
		if (occurredAt == null) {
			occurredAt = Instant.now();
			log.warn(
					"Legacy NotificationInboundEvent is missing occurredAt; using consumption time. eventId={}, type={}",
					event.eventId(), event.type()
			);
		}
		
		// Notification entity'sini event verisinden olustur
		Notification entity = Notification.builder()
				.sourceEventId(event.eventId())
				.recipientId(event.recipientId())
				.type(event.type())
				.title(normalizeTitle(event))
				.message(event.message() == null ? "" : event.message())
				.occurredAt(occurredAt)
				.payload(event.payload())
				.read(false) // yeni bildirim default olarak okunmadi
				.build();
		
		// veritabanina kaydet
		// Flush before any cache/WS/mail side effect. The source_event_id unique
		// constraint is the final concurrency fence when duplicate Rabbit
		// deliveries race on different consumer threads/nodes.
		Notification persisted = notificationRepository.saveAndFlush(entity);
		runAfterCommit(() -> dispatchCommitted(persisted, event));
		log.debug("Notification persistence staged: id={}, user={}, type={}",
				persisted.getId(), persisted.getRecipientId(), persisted.getType());
	}

	private void dispatchCommitted(Notification entity, NotificationInboundEvent event) {
		Long unread = null;
		try {
			unread = notificationRepository.countByRecipientIdAndReadIsFalse(entity.getRecipientId());
			badgeCacheHelper.setUnreadWithTtl(entity.getRecipientId(), unread);
		} catch (Exception e) {
			log.warn("Failed to project unread badge after notification commit. notifId={}, exceptionType={}",
					entity.getId(), e.getClass().getSimpleName());
		}

		try {
			var dto = notificationMapper.toDto(entity);
			if (dto != null && NotificationService.requiresActorIdentityRefresh(dto.type())) {
				dto = notificationService.refreshActorIdentityForDelivery(dto);
			}
			notificationWebSocketService.sendNotificationToUser(
					entity.getRecipientId(), dto);
		} catch (Exception e) {
			log.warn("Notification WebSocket push failed. notifId={}, exceptionType={}",
					entity.getId(), e.getClass().getSimpleName());
		}

		if (unread != null) {
			try {
				notificationWebSocketService.sendUnreadBadgeToUser(entity.getRecipientId(), unread);
			} catch (Exception e) {
				log.warn("Notification badge WebSocket push failed. notifId={}, exceptionType={}",
						entity.getId(), e.getClass().getSimpleName());
			}
		}

		dispatchMail(entity, event);
		log.debug("Committed notification dispatched: id={}, user={}, type={}",
				entity.getId(), entity.getRecipientId(), entity.getType());
	}

	private void dispatchMail(Notification entity, NotificationInboundEvent event) {
		try {
			boolean sendMail = event.emailForce() != null
					? event.emailForce()
					: entity.getType().isEmailRecommended();
			if (!sendMail) return;

			String to = null;
			if (entity.getPayload() != null) {
				Object email = entity.getPayload().get("recipientEmail");
				if (email == null) email = entity.getPayload().get("email");
				if (email != null) to = String.valueOf(email);
			}
			if (to == null || to.isBlank()) {
				log.warn("Notification mail skipped because payload has no email. notifId={}", entity.getId());
				return;
			}

			String subject = "[SoundConnect] "
					+ ((entity.getTitle() == null || entity.getTitle().isBlank())
					? entity.getType().getDefaultTitle()
					: entity.getTitle());
			String text = subject + "\n\n"
					+ (entity.getMessage() != null ? entity.getMessage() + "\n\n" : "")
					+ "Bu e-posta SoundConnect tarafından otomatik gönderildi.";
			mailProducer.send(new MailSendRequest(
					to, subject, null, text, MailKind.NOTIFICATION, entity.getPayload()));
			log.debug("Notification mail queued after commit. notifId={}", entity.getId());
		} catch (Exception e) {
			log.error("Notification mail dispatch failed. notifId={}, exceptionType={}",
					entity.getId(), e.getClass().getSimpleName());
		}
	}

	private void runAfterCommit(Runnable action) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			action.run();
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				action.run();
			}
		});
	}
	
	// event dogrulama methodu. Gerekli alanlar var mi? eksik varsa hata firlat
	private void validate (NotificationInboundEvent e) {
		if (e == null) throw new IllegalArgumentException("event=null");
		if (e.eventId() == null) {
			// Keep an old unidentified delivery in the existing DLQ for operator
			// review. Never invent an ID, loop-requeue, or acknowledge it away.
			throw new AmqpRejectAndDontRequeueException("Notification eventId required for durable replay protection");
		}
		if (e.recipientId() == null) throw new IllegalArgumentException("recipientId required");
		if (e.type() == null) throw new IllegalArgumentException("type required");
		if (e.title() != null && e.title().length() > MAX_TITLE_LENGTH) {
			throw new IllegalArgumentException("title exceeds " + MAX_TITLE_LENGTH + " characters");
		}
		if (e.message() != null && e.message().length() > MAX_MESSAGE_LENGTH) {
			throw new IllegalArgumentException("message exceeds " + MAX_MESSAGE_LENGTH + " characters");
		}
	}

	private String normalizeTitle(NotificationInboundEvent event) {
		return event.title() == null || event.title().isBlank()
				? event.type().getDefaultTitle()
				: event.title();
	}
}
