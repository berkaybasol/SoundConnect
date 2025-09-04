package com.berkayb.soundconnect.modules.notification.entity;

import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Type;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

// columnDefinition: Hibernate'in veritabaaninda olusturacagi kolonun veri tipini elle belirlememizi saglar.
// JSONB: PostgreSQL'de JSON verisini binary formatta saklayan ozel veri tipidir. detaylari unutursan arastiriver :D

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Builder(toBuilder = true)
@Table(
		name = "tbl_notification",
		indexes = {
				@Index(name = "idx_notification_recipient_read", columnList = "recipient_id, is_read"),
				@Index(name = "idx_notification_created_at", columnList = "created_at")
		}
)
public class Notification extends BaseEntity {
	
	@Column(name = "recipient_id", nullable = false, columnDefinition = "uuid")
	private UUID recipientId; // bildirimin gidecegi kullanici
	
	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 64)
	private NotificationType type; // bildirim turu
	
	@Column(name = "title", nullable = false, length = 160)
	private String title; // bildirim basligi
	
	@Column(name = "message", nullable = false, length = 1000)
	private String message;
	
	
	
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", columnDefinition = "jsonb")
	private Map<String, Object> payload; // Bildirime tiklayan kullaniciyi ilgili sayfa/URl'ye yonlendirmek icin kullanicaz
	
	
	@Column(name = "is_read", nullable = false)
	@Builder.Default
	private boolean read = false; // okundu bilgisini tutar default false verdik.
	
	
	
	
}