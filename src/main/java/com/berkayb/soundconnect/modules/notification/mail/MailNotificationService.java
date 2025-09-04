package com.berkayb.soundconnect.modules.notification.mail;

import com.berkayb.soundconnect.modules.notification.entity.Notification;

/**
 * bildirimler icin e-posta gonderim sozlesmesi (MailerSend kullaniyoruz)
 * karar mantigi:
 * - emailForce == TRUE -> her durumda gonder
 * - emailForce == FALSE -> hicbir sekilde gonderme
 * - emailForce == NULL -> NotificationType.emailRecommended() degerine gore karar ver
 */
public interface MailNotificationService {
	
	// E-posta gonderimi gerekiyorsa gonderir, gerekmiyorsa sessizce no-operation yapar.
	// Bu metod idempotent tasarlanmalidir. (ayni Notification icin birden fazla cagrilirsa dublicate mail atilmamali)
	void maybeSendNotificationEmail(Notification notification, Boolean emailForce);
	
	// Type/force mantigini BYPASS ederek dogrudan mail gonderir
	// Sistem ici acil durum senaryolari veya manuel tetiklemeler icin
	void sendEmailForce(Notification notification);
}