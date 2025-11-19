package com.berkayb.soundconnect.modules.collab.ttl.service;

import java.time.LocalDateTime;
import java.util.UUID;

public interface CollabTTLService {
	
	// Yeni daily ilan oluusturunca set edilir.
	void setTTL(UUID collabId, LocalDateTime expirationTime);
	
	// update sirasinda expirationtime degistiyse ttl yenilenir
	void resetTTL(UUID collabId, LocalDateTime newExpiration);
	
	// manuel silmelerde TTL keyi de kaldirmak best practiceymis
	void deleteTTL(UUID collabId);
	
}