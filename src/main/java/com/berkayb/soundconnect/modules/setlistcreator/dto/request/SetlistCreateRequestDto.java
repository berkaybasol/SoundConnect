package com.berkayb.soundconnect.modules.setlistcreator.dto.request;

import java.util.UUID;

public record SetlistCreateRequestDto(
		String name,
		UUID musicianProfileId,
		UUID bandId
) {
}