package com.berkayb.soundconnect.modules.instrument.dto.response;

import java.util.UUID;

public record InstrumentDto(
		UUID id,
		String name
) {
}