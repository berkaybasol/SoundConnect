package com.berkayb.soundconnect.modules.profile.shared.ownership;

import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfilesResolveResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileOwnershipResolverImplTest {

	@Mock private PublicProfileResolverService publicProfileResolverService;
	@Mock private ProfileOwnershipPolicy profileOwnershipPolicy;

	@Test
	void resolvesOnlyAllowedSupportedAndActuallyOwnedProfiles() {
		UUID userId = UUID.randomUUID();
		UUID musicianId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		UUID listenerId = UUID.randomUUID();
		when(profileOwnershipPolicy.supportedTypes()).thenReturn(Set.of(
				ProfileType.MUSICIAN,
				ProfileType.BAND,
				ProfileType.VENUE,
				ProfileType.STUDIO
		));
		when(publicProfileResolverService.resolveByUserId(userId)).thenReturn(
				new UserProfilesResolveResponseDto(userId, List.of(
						new UserProfileTargetDto("MUSICIAN", musicianId, "Ada", "musician-url"),
						new UserProfileTargetDto("BAND", bandId, "Gece Hatti", "band-url"),
						new UserProfileTargetDto("LISTENER", listenerId, "Listener", null)
				))
		);
		when(profileOwnershipPolicy.owns(userId, ProfileType.MUSICIAN, musicianId)).thenReturn(true);
		when(profileOwnershipPolicy.owns(userId, ProfileType.BAND, bandId)).thenReturn(true);

		ProfileOwnershipResolverImpl resolver = new ProfileOwnershipResolverImpl(
				publicProfileResolverService,
				profileOwnershipPolicy
		);

		assertThat(resolver.resolveOwnedProfiles(
				userId,
				Set.of(ProfileType.MUSICIAN, ProfileType.BAND, ProfileType.LISTENER)
		)).containsExactly(
				new OwnedProfileTarget(ProfileType.MUSICIAN, musicianId, "Ada", "musician-url"),
				new OwnedProfileTarget(ProfileType.BAND, bandId, "Gece Hatti", "band-url")
		);
	}

	@Test
	void requireOwnershipReturnsTheCanonicalPublicSnapshot() {
		UUID userId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		when(profileOwnershipPolicy.supportedTypes()).thenReturn(Set.of(ProfileType.BAND));
		when(profileOwnershipPolicy.owns(userId, ProfileType.BAND, bandId)).thenReturn(true);
		when(publicProfileResolverService.resolveByUserId(userId)).thenReturn(
				new UserProfilesResolveResponseDto(userId, List.of(
						new UserProfileTargetDto("BAND", bandId, "Gece Hatti", "band-url")
				))
		);
		ProfileOwnershipResolverImpl resolver = new ProfileOwnershipResolverImpl(
				publicProfileResolverService,
				profileOwnershipPolicy
		);

		assertThat(resolver.requireOwnership(userId, ProfileType.BAND, bandId))
				.isEqualTo(new OwnedProfileTarget(ProfileType.BAND, bandId, "Gece Hatti", "band-url"));
		verify(profileOwnershipPolicy).requireOwnership(userId, ProfileType.BAND, bandId);
	}
}
