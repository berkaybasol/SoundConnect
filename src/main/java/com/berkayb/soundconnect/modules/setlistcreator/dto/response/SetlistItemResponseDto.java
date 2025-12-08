package com.berkayb.soundconnect.modules.setlistcreator.dto.response;

import com.berkayb.soundconnect.modules.setlistcreator.enums.Key;

import java.util.UUID;

public record SetlistItemResponseDto(
		UUID id,
		String artistName,
		String songName,
		Key key,
		Integer orderNumber
) {
}  DTOLAR BITTI MAPPERDAN DEVAM ETCEN