package com.berkayb.soundconnect.shared.messaging.events.notification;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sistemde farkli moduller tarafindan publish edilen Notification moudulunun consume ettigi "bildirim tetikleme" event sozlesmesidir.
 * Amac: Moduller arasi asenkron iletisim kurarak belirli bir kullaniciya ait bildirim olayini NotificationService'e iletmek.
 * Bu event RabbitMQ gibi AMQP(Advanced Message Queuing Protocol) tabanli queue'larla tasinir.
 * NotificationService bu event'i dinlerve bildirimi DB'ye ekler gerekiyorsa mail gonderimi baslatir.
 */

@JsonInclude(JsonInclude.Include.NON_NULL) // JSON ciktisinda null alanlari gizler.
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationInboundEvent(
		UUID recipientId,
		NotificationType type,
		String title,
		String message,
		Map<String, Object> payload,
		Boolean emailForce,
		Instant occurredAt

){
	@Builder
	public NotificationInboundEvent {}
}