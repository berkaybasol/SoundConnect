package com.berkayb.soundconnect.modules.profile.shared.ownership;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Cross-domain authorization policy for profiles a user may act as.
 *
 * <p>This policy intentionally supports only the profile types whose ownership
 * model is currently explicit. Unsupported or incomplete profile types fail
 * closed.</p>
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileOwnershipPolicy {

	private static final Set<ProfileType> SUPPORTED_TYPES = Set.of(
			ProfileType.MUSICIAN,
			ProfileType.BAND,
			ProfileType.VENUE,
			ProfileType.STUDIO
	);

	private final MusicianProfileRepository musicianProfileRepository;
	private final VenueRepository venueRepository;
	private final StudioProfileRepository studioProfileRepository;
	private final BandRepresentationPolicy bandRepresentationPolicy;

	public Set<ProfileType> supportedTypes() {
		return SUPPORTED_TYPES;
	}

	public boolean owns(UUID userId, ProfileType profileType, UUID profileId) {
		if (userId == null || profileType == null || profileId == null) return false;

		return switch (profileType) {
			case MUSICIAN -> musicianProfileRepository.findByUserId(userId)
			                                              .filter(profile -> profileId.equals(profile.getId()))
			                                              .isPresent();
			case BAND -> bandRepresentationPolicy.canRepresent(userId, profileId);
			case VENUE -> venueRepository.findByIdAndOwnerId(profileId, userId).isPresent();
			case STUDIO -> studioProfileRepository.findByUserId(userId)
			                                          .filter(profile -> profileId.equals(profile.getId()))
			                                          .isPresent();
			default -> false;
		};
	}

	public void requireOwnership(UUID userId, ProfileType profileType, UUID profileId) {
		if (!owns(userId, profileType, profileId)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}
}
