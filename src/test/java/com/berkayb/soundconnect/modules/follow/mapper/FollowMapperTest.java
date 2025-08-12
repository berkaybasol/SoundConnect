package com.berkayb.soundconnect.modules.follow.mapper;

import com.berkayb.soundconnect.modules.follow.dto.response.FollowResponseDto;
import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

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
}