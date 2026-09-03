package com.berkayb.soundconnect.modules.pulse.event;


// odadaki anlik mesaji temsil eder
// db ye yazilmaz ve rediste tutulmaz. Websocket ile broadcast edilir

import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.fasterxml.jackson.annotation.JsonInclude;
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

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private ListenerVisibilityMode visibilityMode;

	public void setVisibilityMode(ListenerVisibilityMode visibilityMode) {
		this.visibilityMode = ghostOnly(visibilityMode);
	}

	public static class PulseMessageEventBuilder {
		public PulseMessageEventBuilder visibilityMode(ListenerVisibilityMode visibilityMode) {
			this.visibilityMode = ghostOnly(visibilityMode);
			return this;
		}
	}

	private static ListenerVisibilityMode ghostOnly(ListenerVisibilityMode visibilityMode) {
		return visibilityMode == ListenerVisibilityMode.GHOST ? ListenerVisibilityMode.GHOST : null;
	}
}
