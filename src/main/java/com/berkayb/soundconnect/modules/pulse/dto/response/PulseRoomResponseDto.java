package com.berkayb.soundconnect.modules.pulse.dto.response;

import com.berkayb.soundconnect.modules.pulse.enums.PulseRoomStatus;

import java.time.Instant;
import java.util.UUID;

// pulse ana ekraninda listenecek oda bilgileri. redis state'den uretilir
public record PulseRoomResponseDto(
		UUID roomId,
		Integer roomNumber,
		String topic,
		Instant endsAt,
		Integer activeUserCount,
		PulseRoomStatus status
) {
}