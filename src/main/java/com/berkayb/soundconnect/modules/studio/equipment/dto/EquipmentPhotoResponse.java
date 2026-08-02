package com.berkayb.soundconnect.modules.studio.equipment.dto;

import java.util.UUID;

public record EquipmentPhotoResponse(UUID mediaAssetId, String url, int position) {
}
