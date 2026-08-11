package com.berkayb.soundconnect.modules.collab.dto.response;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;

import java.math.BigDecimal;
import java.util.UUID;

public record CollabActorSummary(
        UUID actorId,
        ProfileType profileType,
        UUID sourceProfileId,
        UUID contactUserId,
        String displayName,
        String avatarUrl,
        BigDecimal rating,
        long reviewCount,
        long completedJobCount
) {}
