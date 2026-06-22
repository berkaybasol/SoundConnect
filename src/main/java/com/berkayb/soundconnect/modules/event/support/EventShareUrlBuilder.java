package com.berkayb.soundconnect.modules.event.support; //eklendi

import org.springframework.beans.factory.annotation.Value; //eklendi
import org.springframework.stereotype.Component; //eklendi

import java.util.UUID; //eklendi

@Component //eklendi
public class EventShareUrlBuilder { //eklendi
	
	private final String publicBaseUrl; //eklendi
	
	public EventShareUrlBuilder(
			@Value("${app.share-base-url}") String publicBaseUrl) { //eklendi
		this.publicBaseUrl = publicBaseUrl; //eklendi
	} //eklendi
	
	public String buildEventShareUrl(UUID eventId) { //eklendi
		String normalized = publicBaseUrl == null ? "" : publicBaseUrl.trim(); //eklendi
		if (normalized.endsWith("/")) { //eklendi
			normalized = normalized.substring(0, normalized.length() - 1); //eklendi
		} //eklendi
		return normalized + "/events/" + eventId; //eklendi
	} //eklendi
}