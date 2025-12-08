package com.berkayb.soundconnect.modules.setlistcreator.dto.request;

import com.berkayb.soundconnect.modules.setlistcreator.enums.Key;

public record SetlistItemRequestDto(
		String artistName,
		String songName,
		Key key,
		Integer orderNumber
) {
}