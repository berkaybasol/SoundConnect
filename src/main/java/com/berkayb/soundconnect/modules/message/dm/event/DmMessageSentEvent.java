package com.berkayb.soundconnect.modules.message.dm.event;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Bir dm mesaji basariyla olusturuldugunda tetiklenen edilen event.
 * WebSocket ile aliciya/sender'a anlik mesaj yayinlanir
 * badge guncellenir
 */
@Getter
@Setter
@Builder
public class DmMessageSentEvent {
	
	private UUID messageId; // mesaj id
	
	private UUID conversationId; // konusma id
	
	private UUID senderId; // gonderici id
	
	private UUID recipientId; // alici id
	
	private String content; // mesaj icerigi
	
	private String messageType; // mesaj tipi text,image,audio vs
	
	private LocalDateTime sentAt; // mesaj yollandigi tarih
	
	
}