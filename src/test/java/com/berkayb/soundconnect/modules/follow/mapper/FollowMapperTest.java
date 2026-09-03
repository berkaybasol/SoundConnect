package com.berkayb.soundconnect.modules.follow.mapper;

import com.berkayb.soundconnect.modules.follow.dto.response.FollowResponseDto;
import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
@Tag("mapper")
class FollowMapperTest {
	
	private final FollowMapper mapper = Mappers.getMapper(FollowMapper.class);
	
	@Test
	void toDto_should_map_all_fields() {
		// given
		UUID fid = UUID.randomUUID();
		UUID gid = UUID.randomUUID();
		
		User follower = new User();
		follower.setId(fid);
		follower.setUsername("followerUser");
		follower.setProfilePicture("follower.png");
		
		User following = new User();
		following.setId(gid);
		following.setUsername("followingUser");
		following.setProfilePicture("following.png");
		
		Follow follow = Follow.builder()
		                      .follower(follower)
		                      .following(following)
		                      .followedAt(LocalDateTime.now())
		                      .build();
		
		// when
		FollowResponseDto dto = mapper.toDto(follow);
		
		// then
		assertNotNull(dto);
		assertEquals(fid, dto.followerId());
		assertEquals("followerUser", dto.followerUsername());
		assertEquals("follower.png", dto.followerProfilePicture());
		
		assertEquals(gid, dto.followingId());
		assertEquals("followingUser", dto.followingUsername());
		assertEquals("following.png", dto.followingProfilePicture());
	}

	@Test
	void toDto_should_use_canonical_listener_identity_for_ghost_users() {
		UUID followerId = UUID.randomUUID();
		UUID followingId = UUID.randomUUID();
		User follower = User.builder()
				.id(followerId)
				.username("professional-name")
				.profilePicture("professional-avatar.png")
				.build();
		User following = User.builder()
				.id(followingId)
				.username("standard-user")
				.profilePicture("standard-avatar.png")
				.build();
		Follow follow = Follow.builder()
				.id(UUID.randomUUID())
				.follower(follower)
				.following(following)
				.followedAt(LocalDateTime.now())
				.build();
		GhostListenerIdentity ghostIdentity = new GhostListenerIdentity(
				followerId,
				"listener-handle",
				"listener-avatar.png",
				ListenerVisibilityMode.GHOST
		);

		FollowResponseDto dto = mapper.toDto(follow, Map.of(followerId, ghostIdentity));

		assertEquals("listener-handle", dto.followerUsername());
		assertEquals("listener-avatar.png", dto.followerProfilePicture());
		assertEquals(ListenerVisibilityMode.GHOST, dto.followerVisibilityMode());
		assertEquals("standard-user", dto.followingUsername());
		assertEquals("standard-avatar.png", dto.followingProfilePicture());
		assertNull(dto.followingVisibilityMode());
	}
}
