package com.berkayb.soundconnect.modules.studio.room.dto.response;

import java.util.UUID;

public record StudioRoomPhotoResponse(UUID mediaAssetId, String url, int orderIndex) {
}
