package com.berkayb.soundconnect.modules.pulse.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * Kullanicinin odadaki anlik varligini temsil eder.
 * Rediste tutulur
 * Kullanici odadan cikinca silinir.
 */

@Data
@Builder
public class PulseUserPresence {
	
	private UUID userId; // kullanici sistemdeki id
	
	private UUID roomId; // kullanicinin bulundugu oda
	
	private Instant joinedAt; // odaya giris zamani (istatistik ve abuse analizi icin)
	
	private Instant lastActivityAt; // kullanicinin soin aktif oldu an (heartbeat / idle check icin)
	
	
	
}