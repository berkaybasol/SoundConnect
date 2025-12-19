package com.berkayb.soundconnect.modules.pulse.dto.request;

import java.util.UUID;

public record PulseVoteRequestDto(
		UUID roomId,
		String topic
) {
}