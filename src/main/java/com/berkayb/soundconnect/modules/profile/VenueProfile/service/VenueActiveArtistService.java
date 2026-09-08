package com.berkayb.soundconnect.modules.profile.VenueProfile.service;

import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueActiveArtistDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.enums.VenueActiveArtistType;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueActiveArtistRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
// Keep content and its count in one snapshot if a connection changes during this request.
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class VenueActiveArtistService {
    private final VenueActiveArtistRepository repository;

    public PageResponse<VenueActiveArtistDto> list(UUID venueId, VenueActiveArtistType type,
                                                 String query, int page, int size) {
        // Validate here as well as at the HTTP boundary so other callers cannot bypass limits.
        if (venueId == null || type == null || page < 0 || page > 10_000 || size < 1 || size > 50
                || (query != null && query.length() > 100)) {
            throw new SoundConnectException(ErrorType.VALIDATION_ERROR);
        }
        if (!repository.existsVenueProfile(venueId)) {
            throw new SoundConnectException(ErrorType.VENUE_NOT_FOUND);
        }
        String term = query == null ? "" : query.strip();
        var pageable = PageRequest.of(page, size);
        var artists = switch (type) {
            case MUSICIAN -> repository.findMusicians(venueId, term, pageable);
            case BAND -> repository.findBands(venueId, term, pageable);
        };
        return PageResponse.from(artists.map(row -> new VenueActiveArtistDto(
                row.id(), displayName(row.name(), type), type, row.profilePictureUrl())));
    }

    private String displayName(String name, VenueActiveArtistType type) {
        if (name != null && !name.isBlank()) return name.strip();
        return type == VenueActiveArtistType.BAND ? "Grup" : "Sanatçı";
    }
}
