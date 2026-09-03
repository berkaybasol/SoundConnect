package com.berkayb.soundconnect.modules.profile.shared.media.service;

import com.berkayb.soundconnect.modules.media.mapper.MediaAssetMapper;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.support.ListenerVisibilityPolicy;
import com.berkayb.soundconnect.modules.profile.shared.media.dto.response.ProfileMediaUiResponseDto;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.track.service.TrackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProfileMediaUiServiceImplTest {

	@Mock ProfileMediaService profileMediaService;
	@Mock MediaAssetService mediaAssetService;
	@Mock MediaAssetMapper mediaAssetMapper;
	@Mock TrackService trackService;
	@Mock ListenerVisibilityPolicy listenerVisibilityPolicy;

	private ProfileMediaUiServiceImpl service;

	@BeforeEach
	void setUp() {
		service = new ProfileMediaUiServiceImpl(
				profileMediaService,
				mediaAssetService,
				mediaAssetMapper,
				trackService,
				listenerVisibilityPolicy
		);
	}

	@Test
	void ghostListenerProfileNeverEnumeratesFrozenShowcaseMedia() {
		UUID profileId = UUID.randomUUID();
		when(listenerVisibilityPolicy.lockForReadAndIsPubliclyRestrictedProfile(profileId))
				.thenReturn(true);

		ProfileMediaUiResponseDto response = service.getProfileMedia(ProfileType.LISTENER, profileId);

		assertThat(response.featuredVideo()).isNull();
		assertThat(response.videos()).isEmpty();
		assertThat(response.audios()).isEmpty();
		verifyNoInteractions(profileMediaService, mediaAssetService, mediaAssetMapper, trackService);
	}
}
