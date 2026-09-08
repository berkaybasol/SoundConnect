package com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response;

import com.berkayb.soundconnect.modules.profile.VenueProfile.enums.VenueActiveArtistType;

import java.util.UUID;

/** Public directory data only. Connection request details are deliberately not exposed. */
public record VenueActiveArtistDto(
        UUID id,
        String name,
        VenueActiveArtistType type,
        String profilePictureUrl
) {
}
