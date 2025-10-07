package com.berkayb.soundconnect.modules.message.dm.event;

public interface DmMessageEventPublisher {
	/**
	 * Dm mesaji ile ilgili modul event'lerini publish eden arayuz
	 * Dm mesaji gonderildi eventi
	 */
	void publishMessageSentEvent(DmMessageSentEvent event);
}