package com.berkayb.soundconnect.modules.profile.shared.ownership;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileOwnershipPolicyTest {

	@Mock private MusicianProfileRepository musicianProfileRepository;
	@Mock private VenueRepository venueRepository;
	@Mock private StudioProfileRepository studioProfileRepository;
	@Mock private BandRepresentationPolicy bandRepresentationPolicy;

	private ProfileOwnershipPolicy policy;

	@BeforeEach
	void setUp() {
		policy = new ProfileOwnershipPolicy(
				musicianProfileRepository,
				venueRepository,
				studioProfileRepository,
				bandRepresentationPolicy
		);
	}

	@Test
	void checksEachSupportedProfileAgainstItsCanonicalOwnerRelationship() {
		UUID userId = UUID.randomUUID();
		UUID musicianId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		UUID venueId = UUID.randomUUID();
		UUID studioId = UUID.randomUUID();
		when(musicianProfileRepository.findByUserId(userId))
				.thenReturn(Optional.of(MusicianProfile.builder().id(musicianId).build()));
		when(bandRepresentationPolicy.canRepresent(userId, bandId)).thenReturn(true);
		when(venueRepository.findByIdAndOwnerId(venueId, userId))
				.thenReturn(Optional.of(Venue.builder().id(venueId).build()));
		when(studioProfileRepository.findByUserId(userId))
				.thenReturn(Optional.of(StudioProfile.builder().id(studioId).build()));

		assertThat(policy.owns(userId, ProfileType.MUSICIAN, musicianId)).isTrue();
		assertThat(policy.owns(userId, ProfileType.BAND, bandId)).isTrue();
		assertThat(policy.owns(userId, ProfileType.VENUE, venueId)).isTrue();
		assertThat(policy.owns(userId, ProfileType.STUDIO, studioId)).isTrue();
	}

	@Test
	void unsupportedProfileTypesFailClosedWithoutRepositoryLookups() {
		assertThat(policy.owns(UUID.randomUUID(), ProfileType.LISTENER, UUID.randomUUID())).isFalse();

		verifyNoInteractions(
				musicianProfileRepository,
				venueRepository,
				studioProfileRepository,
				bandRepresentationPolicy
		);
	}

	@Test
	void requireOwnershipUsesForbiddenInsteadOfLeakingWhetherTargetExists() {
		UUID userId = UUID.randomUUID();
		UUID profileId = UUID.randomUUID();
		when(musicianProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> policy.requireOwnership(userId, ProfileType.MUSICIAN, profileId))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(error -> assertThat(((SoundConnectException) error).getErrorType())
						.isEqualTo(ErrorType.FORBIDDEN_ACCESS));
	}
}
