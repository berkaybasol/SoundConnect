package com.berkayb.soundconnect.modules.profile.MusicianProfile.repository;

import java.util.UUID;

/** Scalar public projection; unavailable venues never enter media enrichment. */
public record MusicianProfileVenueRow(UUID venueId, String venueName, UUID profilePictureMediaId) {}
