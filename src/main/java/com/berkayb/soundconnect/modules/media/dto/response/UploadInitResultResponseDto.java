package com.berkayb.soundconnect.modules.media.dto.response;

import lombok.Builder;

import java.util.UUID;

@Builder
public record UploadInitResultResponseDto(
		UUID assetId,
		String uploadUrl
) {


}