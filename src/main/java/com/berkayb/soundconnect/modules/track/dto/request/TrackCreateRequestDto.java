package com.berkayb.soundconnect.modules.track.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record TrackCreateRequestDto(
		@NotNull UUID mediaAssetId,
		@NotBlank @Size(max = 160) String title,
		@Min(1) @Max(86400) Integer durationSeconds,
		@Min(20) @Max(400) Integer bpm
) {
}
