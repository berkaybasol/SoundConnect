package com.berkayb.soundconnect.modules.media.dto.request;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.UUID;

@Builder
public record UploadInitRequestDto(
		@NotNull
		MediaOwnerType ownerType,
		
		@NotNull
		UUID ownerId,
		
		@NotNull
		MediaKind kind,
		
		@NotNull
		MediaVisibility visibility,
		
		@NotBlank
		@Size(max = 64)
		String mimeType,
		
		@Positive // 0 ve negatif sayilari gecersiz kilar
		long sizeBytes,
		
		@Size(max = 255)
		String originalFileName
){

}