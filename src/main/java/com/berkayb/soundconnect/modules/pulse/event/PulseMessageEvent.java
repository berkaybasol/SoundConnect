package com.berkayb.soundconnect.modules.pulse.event;


// odadaki anlik mesaji temsil eder
// db ye yazilmaz ve rediste tutulmaz. Websocket ile broadcast edilir

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class PulseMessageEvent {
	
	private UUID roomId; // mesajin gonderildigi oda id
	
	private UUID userId; // mesaji gonderen kullanici
	
	private String username; // ui tarafinda gosterilecek kullanici adi // sadece ui icin
	
	private String content; // kullanicinin yazdigi mesaj
	
	private Instant sentAt; // mesajin gonderildigi an
	
	private String profileImageUrl;
}