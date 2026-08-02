package com.berkayb.soundconnect.modules.studio.room.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record StudioRoomArchiveRequest(@NotNull @Min(0) Long expectedVersion) {
}
