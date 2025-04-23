package com.berkayb.soundconnect.modules.instrument.dto.request;

import java.util.UUID;

public record InstrumentSaveRequestDto(
		String name,
		UUID userId
) {
}