package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository;

import java.util.UUID;

/** Internal page projection. Media identifiers are resolved before building the API response. */
public record BandPendingInvitationRow(
		UUID userId,
		String username,
		UUID profilePictureMediaId
) {}
