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
	SOCIAL_NEW_BAND_FOLLOWER("SOCIAL", "Yeni band takipçisi", false),
	// SOCIAL_MENTION("SOCIAL", "Bahsedildin", false),
	// SOCIAL_LIKE("SOCIAL", "İçeriğin beğenildi", false),
	// SOCIAL_COMMENT("SOCIAL", "İçeriğine yorum geldi", false),
	
	
	// DM
	DM_NEW_MESSAGE("DM", "Yeni mesaj", false),

	// STUDIO
	STUDIO_RESERVATION_CREATED("STUDIO", "Yeni stüdyo rezervasyonu", false),
	STUDIO_RESERVATION_CONFLICTING_REQUESTS("STUDIO", "Çakışan rezervasyon talepleri", false),
	STUDIO_RESERVATION_APPROVED("STUDIO", "Rezervasyon talebi onaylandı", false),
	STUDIO_RESERVATION_REJECTED("STUDIO", "Rezervasyon talebi reddedildi", false),
	STUDIO_RESERVATION_CANCELLED_BY_STUDIO("STUDIO", "Stüdyo rezervasyonu iptal edildi", false),
	
	// VENUE
	VENUE_APPLICATION_REJECTED("VENUE", "Mekan başvurun reddedildi", true),
	
	// ARTISTVENUELINKAPPLICATION
	ARTIST_VENUE_LINK_APPLICATION_REQUEST("ARTIST_VENUE", "Bağlanma isteği gönderildi", false),
	ARTIST_VENUE_LINK_APPLICATION_ACCEPT("ARTIST_VENUE", "Bağlanma isteğin onaylandı", false),
	ARTIST_VENUE_LINK_APPLICATION_REJECT("ARTIST_VENUE", "Bağlanma isteğin reddedildi", false),
	
	// TABLE GROUP (Muzik birlestirir)
	TABLE_JOIN_REQUEST_RECEVIED("TABLE","Yeni başvuru isteği", false),
	TABLE_JOIN_REQUEST_APPROVED("TABLE","Başvurun onaylandı",false),
	TABLE_JOIN_REQUEST_REJECTED("TABLE","Başvurun reddedildi",false),
	TABLE_PARTICIPANT_LEFT("TABLE","Katılımcı ayrıldı",false),
	TABLE_REMOVED ("TABLE","Masadan çıkarıldın",false),
	TABLE_CANCELLED ("TABLE","Masa etkinliği iptal edildi.",false),
	TABLE_EXPIRED("TABLE","Masa süresi doldu",false),
	
	// BAND
	BAND_INVITE_RECEIVED("BAND","Band daveti alındı",false),
	BAND_INVITE_ACCEPTED("BAND","Band daveti kabul edildi",false),
	BAND_INVITE_REJECTED("BAND","Band daveti reddedildi",false),
	BAND_MEMBER_REMOVED("BAND","Banddan çıkarıldın",false),
	BAND_MEMBER_LEFT("BAND","Band üyesi ayrıldı",false),
	
	// OVERTHINKING
	OVERTHINKING_REVEAL_REQUEST_RECEIVED("OVERTHINKING","Profil görüntüleme isteği alındı",false),
	OVERTHINKING_REVEAL_REQUEST_APPROVED("OVERTHINKING","Profil görüntüleme isteği kabul edildi",false),
	OVERTHINKING_REVEAL_REQUEST_REJECTED("OVERTHINKING","Profil görüntüleme isteği reddedildi",false);
	
	
	
	//FIXME diger modulleri de gelistirdikce eklemeyi unutma
	
	
	
	// domain etiketi
	private final String category;
	
	// UI'da gosterilebilecek default kisa baslik
	private final String defaultTitle;
	
	// E-posta gonderilsin mi?
	private final boolean emailRecommended;
	
}
