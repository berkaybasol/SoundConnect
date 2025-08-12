package com.berkayb.soundconnect.modules.instrument.dto.request;

import jakarta.validation.constraints.NotBlank;

public record InstrumentSaveRequestDto(
		@NotBlank
		String name
) {
}