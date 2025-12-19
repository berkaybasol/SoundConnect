package com.berkayb.soundconnect.modules.pulse.service;


import com.berkayb.soundconnect.modules.pulse.model.PulseRoomState;

import java.util.UUID;

/**
 * Pulse odalarinin temel is mantigini yoneten service interface.
 * - oda olusturma
 * - oda bilgisi getirme
 * - oda durumunu degistirme
 * - kullanici giris/cikis islemleri
 * - oda sayac kontrolu
 */
public interface PulseRoomService {
	
	/*
	Yeni bir pulse odasi olusturur. oda acildiginda:
	- varsayilan konu set edilir
	- baslangic bitis zamani hesaplanir (30dk)
	- Redis'e kaydedilir
	 */
	PulseRoomState createRoom(String initialTopic);
	
	// odanin regres statesini redisten okur
	PulseRoomState getRoom(UUID roomId);
	
	// odayi bitirir
	void endRoom(UUID roomId);
	
	// odanin konu bilgisini degistirir (new cycle basladiginda)
	void updateTopic(UUID roomId, String newTopic);
	
	// kullanici odanin online listesine eklenir. Redis SET -> SADD
	void userJoin(UUID roomId, UUID userId);
	
	// kullanici odadan cikar Redis SET - SREM
	void userLeave(UUID roomId, UUID userId);
	
	// odanin aktif kullanici sayisini dondurur
	int getActiveUserCount(UUID roomId);
	
	// kullanici odada mi?
	boolean isUserInRoom(UUID roomId, UUID userId);
	
	/**
	 * Her X saniyede bir scheduler tarafindan tetiklenecek/
	 * - oda suresi bitti mi?
	 * - yeni tur baslamali mi?
	 * - kapanmis odalar temizlenecek mi?
	 *
	 * Not: bu production icin en kiritik kisim
	 */
	void tickRoomLifecycle();
	
	int getCooldownSeconds();
}