package com.berkayb.soundconnect.modules.pulse.model;

// Bir kullanicinin bir pulse odasinda verdigi oyu temsil eder.

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

@Data
@Builder
public class PulseVote {
	
	private UUID roomId;
	
	private UUID userId;
	
	private String topic; // oy verilen konu
	
	
}