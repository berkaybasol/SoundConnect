package com.berkayb.soundconnect.modules.notification.enums;

//FIXME bildirim isteyen modulleri bitirdikce buraya gel..

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum NotificationType {
	
	// AUTH
	AUTH_EMAIL_VERIFIED("AUTH", "E-Posta doğrulandı", true),
	AUTH_RESET_PASSWORD("AUTH", "Şifre sıfırlama talimatı", true),
	
	// MEDIA
	MEDIA_UPLOAD_RECEVIED("MEDIA", "Yükleme alındı", false),
	MEDIA_TRANSCODE_READY("MEDIA", "Medya hazır (izlenebilir)", false),
	MEDIA_TRANSCODE_FAILED("MEDIA", "Medya işleme başarısız", true),
	
	// SOCIAL
	SOCIAL_NEW_FOLLOWER("SOCIAL", "Yeni takipçi", false),
	SOCIAL_NEW_BAND_FOLLOWER("SOCIAL", "Yeni band takipcisi", false),
	// SOCIAL_MENTION("SOCIAL", "Bahsedildin", false),
	// SOCIAL_LIKE("SOCIAL", "İçeriğin beğenildi", false),
	// SOCIAL_COMMENT("SOCIAL", "İçeriğine yorum geldi", false),
	
	
	// DM
	DM_NEW_MESSAGE("DM", "Yeni mesaj", false),
	
	// VENUE
	VENUE_APPLICATION_REJECTED("VENUE", "Mekan başvurun reddedildi", true),
	
	// ARTISTVENUELINKAPPLICATION
	ARTIST_VENUE_LINK_APPLICATION_REQUEST("ARTIST_VENUE", "Bağlanma isteği gönderildi", false),
	ARTIST_VENUE_LINK_APPLICATION_ACCEPT("ARTIST_VENUE", "Bağlanma isteğin onaylandı", false),
	ARTIST_VENUE_LINK_APPLICATION_REJECT("ARTIST_VENUE", "Bağlanma isteğin reddedildi", false),
	
	// TABLE GROUP (Muzik birlestirir)
	TABLE_JOIN_REQUEST_RECEVIED("TABLE","Yeni basvuru istegi", false),
	TABLE_JOIN_REQUEST_APPROVED("TABLE","Basvurun onaylandi",false),
	TABLE_JOIN_REQUEST_REJECTED("TABLE","Basvurun reddedildi",false),
	TABLE_PARTICIPANT_LEFT("TABLE","Katilimci ayrildi",false),
	TABLE_REMOVED ("TABLE","Masadan cikarildin",false),
	TABLE_CANCELLED ("TABLE","Masa etkinligi iptal edildi.",false),
	TABLE_EXPIRED("TABLE","Masa suresi doldu",false),
	
	// BAND
	BAND_INVITE_RECEIVED("BAND","Band daveti alindi",false),
	BAND_INVITE_ACCEPTED("BAND","Band daveti kabul edildi",false),
	BAND_INVITE_REJECTED("BAND","Band daveti reddedildi",false),
	BAND_MEMBER_REMOVED("BAND","Bandden cikarildin",false),
	BAND_MEMBER_LEFT("BAND","Band uyesi ayrildi",false),
	
	// OVERTHINKING
	OVERTHINKING_REVEAL_REQUEST_RECEIVED("OVERTHINKING","Profil goruntuleme istegi alindi",false),
	OVERTHINKING_REVEAL_REQUEST_APPROVED("OVERTHINKING","Profil goruntuleme istegi kabul edildi",false),
	OVERTHINKING_REVEAL_REQUEST_REJECTED("OVERTHINKING","Profil goruntuleme istegi reddedildi",false);
	
	
	
	//FIXME diger modulleri de gelistirdikce eklemeyi unutma
	
	
	
	// domain etiketi
	private final String category;
	
	// UI'da gosterilebilecek default kisa baslik
	private final String defaultTitle;
	
	// E-posta gonderilsin mi?
	private final boolean emailRecommended;
	
}
