package com.berkayb.soundconnect.modules.engagement.service;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;

import java.util.UUID;

public interface EngagementTargetValidator {
	// verilen targetType ve targetId sistemde var mi kontrol eder. eger yoksa exception firlatir
	void validateExists(EngagementTargetType targetType, UUID targedId);
	
}