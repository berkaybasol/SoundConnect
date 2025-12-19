package com.berkayb.soundconnect.modules.pulse.event;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

// oylama esnasinda UI'in anlik olarak guncellenmesi icin broadcast edilir
@Data
@Builder
public class PulseVoteUpdateEvent {
	private UUID roomId;
	
	// topic -> voteCount
	private Map<String, Integer> results;
}