package com.berkayb.soundconnect.modules.notification.listener;

import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mail.MailNotificationService;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
	private final NotificationRepository notificationRepository;
	private final NotificationBadgeCacheHelper badgeCacheHelper;
	private final NotificationMapper notificationMapper;
	private final NotificationWebSocketService notificationWebSocketService;
	private final MailNotificationService mailNotificationService;
	
	
	// RabbitMQ'dan notification queue'undan mesajlari dinler. her gelen event icin bu method cagrilir
	@Transactional
	@RabbitListener(queues = "${app.messaging.notification.queue:notification.queue}")
	public void handle (NotificationInboundEvent event) {
		// event dogrulamasini yap. eksik veya hatali ise isleme alma log bas ve cik
		try {
			validate(event);
		} catch (IllegalArgumentException e) {
			log.warn("Invalid NotificationInboundEvent, skipping. err={}, event={}", e.getMessage(), event);
			return;
		}
		
		// Notification entity'sini event verisinden olustur
		Notification entity = Notification.builder()
				.recipientId(event.recipientId())
				.type(event.type())
				.title(event.title())
				.message(event.message())
				.payload(event.payload())
				.read(false) // yeni bildirim default olarak okunmadi
				.build();
		
		// veritabanina kaydet
		entity = notificationRepository.save(entity);
		
		
		// okunmamis notification sayisini guncelle (Redis'e badge cache olarak setle)
		try {
			// ilgili kullanicinin okunmamis notification sayisini db'den al
			long unread = notificationRepository.countByRecipientIdAndReadIsFalse(event.recipientId());
			
			// sayaci redis'e ttl ile yaz
			badgeCacheHelper.setUnreadWithTtl(event.recipientId(), unread);
		} catch (Exception e) {
			// cache guncelleme basarisiz olursa sadece logla. sureci kirma
			log.warn("Failed to update unread badge cache for user={}, err={}", event.recipientId(), e.toString());
		}
		
		// WebSocket push (DTO)
		try {
			var dto = notificationMapper.toDto(entity);
			notificationWebSocketService.sendNotificationToUser(
					entity.getRecipientId(),
					dto
			);
			// opsiyonel: badge'i anlik olarak guncelle (cache'deki degeri yayinla)
			Long cacheUnread = badgeCacheHelper.getCacheUnread(entity.getRecipientId());
			notificationWebSocketService.sendUnreadBadgeToUser(
					entity.getRecipientId(),
					cacheUnread != null ? cacheUnread: 0L
			);
		} catch (Exception e) {
			log.warn("WS push failed for notifId={}, user={}, err={}",
			         entity.getId(), entity.getRecipientId(), e.toString());
		}
		
		try {
			mailNotificationService.maybeSendNotificationEmail(entity, event.emailForce());
		} catch (Exception e) {
			log.error("Mail send failed for notifId={}, user={}, err={}",
			          entity.getId(), entity.getRecipientId(), e.toString());
		}
		
		// TODO: elasticSearchService.indexNotification(entity);
		
		log.debug("Notification persisted & dispatched: id={}, user={}, type={}",
		          entity.getId(), entity.getRecipientId(), entity.getType());
	}
	
	// event dogrulama methodu. Gerekli alanlar var mi? eksik varsa hata firlat
	private void validate (NotificationInboundEvent e) {
		if (e == null) throw new IllegalArgumentException("event=null");
		if (e.recipientId() == null) throw new IllegalArgumentException("recipientId required");
		if (e.type() == null) throw new IllegalArgumentException("type required");
		// title/message opsiyonel. UI/mapper defaultTitle ile handle ediyor.
	}
}