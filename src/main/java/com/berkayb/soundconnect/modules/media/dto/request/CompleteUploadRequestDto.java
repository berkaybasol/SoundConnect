package com.berkayb.soundconnect.modules.media.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import java.util.UUID;

@Builder
public record CompleteUploadRequestDto(
		@NotNull UUID assetId
) {
}