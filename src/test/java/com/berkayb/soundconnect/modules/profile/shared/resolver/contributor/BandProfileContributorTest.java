package com.berkayb.soundconnect.modules.profile.shared.resolver.contributor;

import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandRepresentationPolicy;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BandProfileContributorTest {

	@Mock
	private BandRepresentationPolicy bandRepresentationPolicy;

	@Mock
	private MediaAssetService mediaAssetService;

	@Test
	void mapsRepresentableBandToPublicProfileTarget() {
		UUID userId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		UUID mediaId = UUID.randomUUID();
		Band band = Band.builder()
		                .id(bandId)
		                .name("Gece Hatti")
		                .profilePictureMediaId(mediaId)
		                .build();
		when(bandRepresentationPolicy.findRepresentableBands(userId)).thenReturn(List.of(band));
		when(mediaAssetService.getDisplayUrl(mediaId)).thenReturn("https://cdn.example/band.webp");

		BandProfileContributor contributor = new BandProfileContributor(
				bandRepresentationPolicy,
				mediaAssetService
		);

		assertThat(contributor.resolve(userId)).containsExactly(new UserProfileTargetDto(
				"BAND",
				bandId,
				"Gece Hatti",
				"https://cdn.example/band.webp"
		));
	}

	@Test
	void mediaFailureDoesNotHideAnOtherwiseValidBand() {
		UUID userId = UUID.randomUUID();
		UUID mediaId = UUID.randomUUID();
		Band band = Band.builder()
		                .id(UUID.randomUUID())
		                .name("Gece Hatti")
		                .profilePictureMediaId(mediaId)
		                .build();
		when(bandRepresentationPolicy.findRepresentableBands(userId)).thenReturn(List.of(band));
		when(mediaAssetService.getDisplayUrl(mediaId)).thenThrow(new IllegalStateException("storage unavailable"));

		BandProfileContributor contributor = new BandProfileContributor(
				bandRepresentationPolicy,
				mediaAssetService
		);

		assertThat(contributor.resolve(userId).getFirst().profilePictureUrl()).isNull();
	}
}
