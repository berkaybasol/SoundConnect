package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response;

import java.util.UUID;

/** Private founder-only invitation summary, not an active/public roster member. */
public record BandPendingInvitationResponseDto(
        UUID userId,
        String username,
        String profilePicture,
        String status
) {}
