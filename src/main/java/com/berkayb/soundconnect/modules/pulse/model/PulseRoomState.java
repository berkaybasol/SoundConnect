package com.berkayb.soundconnect.modules.pulse.model;

import com.berkayb.soundconnect.modules.pulse.enums.PulseRoomStatus;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * PulseRoomState
 *
 * Bir pulse odasinin anlik durumunu temsil eder
 * - Redist'te tutulur entity degildir db yok
 * - Oda aktif oldukca yasar oda bitince silinir.
 */
@Data
@Builder
public class PulseRoomState {

	private UUID roomId; // odanin sistemsel id'si (redis key olarak da kullanilabilir)
	
	private Integer roomNumber; // Kullaniciya gosterilecek oda numarasi Oda#12 gibi. UX icindir teknik olarak bi anlami yok
	
	private String topic; // o an konusulan konu. oda kapanmaz, konu deigisir.
	
	private Instant endsAt; // odanin planlanan bitis zamani (geri sayim icin kullancaz)
	
	private Integer activeUserCount; // odadaki aktif kullanici sayisi. redis uzerinden atomik guncellenir.
	
	private PulseRoomStatus status; // oda durumu ACTIVE -> VOTING -> CLOSING
}