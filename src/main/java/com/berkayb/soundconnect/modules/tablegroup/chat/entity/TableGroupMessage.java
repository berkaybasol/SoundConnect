package com.berkayb.soundconnect.modules.tablegroup.chat.entity;

import com.berkayb.soundconnect.modules.tablegroup.chat.enums.MessageType;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.UUID;


@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table( name = "tbl_table_group_message",
		indexes = {
				// Belirli bir masaya (tableGroupId) ait mesajlari hizli cekmek icin
				@Index(
						name = "idx_tg_msg_group_created",
						columnList = "table_group_id, created_at"
				),
				// Sender bazlı filtreler ileride gerekebilir (moderasyon vs)
				@Index(
						name = "idx_tg_msg_sender",
						columnList = "sender_id"
				)
		}
)
public class TableGroupMessage extends BaseEntity {
	
	// mesaj hangi masaya ait?
	@Column(name = "table_group_id", nullable = false, columnDefinition = "uuid")
	private UUID tableGroupId;
	
	// mesaji kim gonderdi?
	@Column(name = "sender_id", nullable = false, columnDefinition = "uuid")
	private UUID senderId;
	
	// mesaj govdesi. TEXT kullaniyoruz uzun icerikler icin guvenli
	@Column(name = "content", nullable = false, columnDefinition = "TEXT")
	private String content;
	
	// Mesaj tipi sistem mesajlari, normal kullanici mesaji ve belki image icerir :D
	@Column(name = "message_type", nullable = false, length = 32)
	@Enumerated(EnumType.STRING)
	private MessageType messageType;
	
	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;
}