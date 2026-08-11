package com.berkayb.soundconnect.modules.profile.shared.resolver.service;

import com.berkayb.soundconnect.modules.profile.shared.resolver.contributor.PublicProfileContributor;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicProfileResolverServiceImplTest {

	@Test
	void sortsBandBetweenMusicianAndVenue() {
		UUID userId = UUID.randomUUID();
		PublicProfileContributor contributor = mock(PublicProfileContributor.class);
		when(contributor.resolve(userId)).thenReturn(List.of(
				new UserProfileTargetDto("STUDIO", UUID.randomUUID(), "Studio", null),
				new UserProfileTargetDto("VENUE", UUID.randomUUID(), "Venue", null),
				new UserProfileTargetDto("BAND", UUID.randomUUID(), "Band", null),
				new UserProfileTargetDto("MUSICIAN", UUID.randomUUID(), "Musician", null)
		));

		PublicProfileResolverServiceImpl service = new PublicProfileResolverServiceImpl(List.of(contributor));

		assertThat(service.resolveByUserId(userId).profiles())
				.extracting(UserProfileTargetDto::type)
				.containsExactly("MUSICIAN", "BAND", "VENUE", "STUDIO");
	}
}
