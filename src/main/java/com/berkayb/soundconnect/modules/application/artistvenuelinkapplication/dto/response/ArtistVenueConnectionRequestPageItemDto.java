package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.dto.response;

import com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.enums.RequestByType;
import java.util.UUID;

/** Private management row with current artist identity; never exposes user/profile entities. */
public record ArtistVenueConnectionRequestPageItemDto(
        UUID id, UUID musicianProfileId, UUID bandId, UUID venueId,
        String musicianStageName, String bandName, String bandProfilePictureUrl,
        String venueProfilePictureUrl, String venueName, String message, String status,
        RequestByType requestByType, String createdAt,
        String musicianProfilePictureUrl, String musicianUsername, String musicianDisplayName
) {
    public static ArtistVenueConnectionRequestPageItemDto from(ArtistVenueConnectionRequestResponseDto row,
            String musicianProfilePictureUrl, String musicianUsername, String musicianDisplayName) {
        return new ArtistVenueConnectionRequestPageItemDto(row.id(), row.musicianProfileId(), row.bandId(), row.venueId(),
                row.musicianStageName(), row.bandName(), row.bandProfilePictureUrl(), row.venueProfilePictureUrl(),
                row.venueName(), row.message(), row.status(), row.requestByType(), row.createdAt(),
                musicianProfilePictureUrl, musicianUsername, musicianDisplayName);
    }
}
