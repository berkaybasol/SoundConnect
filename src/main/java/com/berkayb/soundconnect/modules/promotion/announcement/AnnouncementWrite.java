package com.berkayb.soundconnect.modules.promotion.announcement;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record AnnouncementWrite(
        @NotBlank @Size(max = 150) String title,
        @NotBlank @Size(max = 5000) String body,
        @NotEmpty @Size(max = 4) Set<ProfileType> targetProfiles,
        UUID mediaAssetId,
        @PositiveOrZero Long expectedVersion
) { }
