package com.berkayb.soundconnect.modules.follow.band.mapper;

import com.berkayb.soundconnect.modules.follow.band.dto.response.BandFollowResponseDto;
import com.berkayb.soundconnect.modules.follow.band.entity.BandFollow;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BandFollowMapperTest {

	private final BandFollowMapper mapper = Mappers.getMapper(BandFollowMapper.class);

	@Test
	void ghostFollowerUsesCanonicalListenerIdentityWithoutChangingBandIdentity() {
		UUID followerId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		UUID bandAvatarId = UUID.randomUUID();
		User follower = User.builder()
				.id(followerId)
				.username("alternate-profile-name")
				.profilePicture("alternate-avatar.png")
				.build();
		Band band = Band.builder()
				.id(bandId)
				.name("Northern Lights")
				.profilePictureMediaId(bandAvatarId)
				.build();
		BandFollow follow = BandFollow.builder()
				.id(UUID.randomUUID())
				.follower(follower)
				.band(band)
				.followedAt(LocalDateTime.now())
				.build();
		GhostListenerIdentity identity = new GhostListenerIdentity(
				followerId,
				"listener-handle",
				"listener-avatar.png",
				ListenerVisibilityMode.GHOST
		);

		BandFollowResponseDto dto = mapper.toDto(follow, identity);

		assertThat(dto.followerId()).isEqualTo(followerId);
		assertThat(dto.followerUsername()).isEqualTo("listener-handle");
		assertThat(dto.followerProfilePicture()).isEqualTo("listener-avatar.png");
		assertThat(dto.followerVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
		assertThat(dto.bandId()).isEqualTo(bandId);
		assertThat(dto.bandName()).isEqualTo("Northern Lights");
		assertThat(dto.bandProfilePictureMediaId()).isEqualTo(bandAvatarId);
	}
}
