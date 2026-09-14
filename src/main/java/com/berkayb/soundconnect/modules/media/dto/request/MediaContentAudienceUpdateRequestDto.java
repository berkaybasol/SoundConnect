package com.berkayb.soundconnect.modules.media.dto.request;

import com.berkayb.soundconnect.modules.media.enums.MediaContentAudience;
import jakarta.validation.constraints.NotNull;

public record MediaContentAudienceUpdateRequestDto(@NotNull MediaContentAudience contentAudience) { }
