package com.berkayb.soundconnect.modules.overthinking.service;

import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;

public interface OverthinkingNotificationService {
	
	void sendRevealRequestReceivedNotification(OverthinkingRevealRequest request);
	
	void sendRevealRequestApprovedNotification(OverthinkingRevealRequest request);
	
	void sendRevealRequestRejectedNotification(OverthinkingRevealRequest request);
}