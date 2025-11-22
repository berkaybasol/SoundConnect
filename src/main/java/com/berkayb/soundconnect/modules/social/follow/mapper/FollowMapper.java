package com.berkayb.soundconnect.modules.social.follow.mapper;

import com.berkayb.soundconnect.modules.social.follow.dto.response.FollowResponseDto;
import com.berkayb.soundconnect.modules.social.follow.entity.Follow;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * FollowMapper - Entity’den DTO’ya dönüşümü otomatik yapar.
 */
@Mapper(componentModel = "spring")
public interface FollowMapper {
	
	@Mapping(source = "follower.id", target = "followerId")
	@Mapping(source = "follower.username", target = "followerUsername")
	@Mapping(source = "follower.profilePicture", target = "followerProfilePicture")
	@Mapping(source = "following.id", target = "followingId")
	@Mapping(source = "following.username", target = "followingUsername")
	@Mapping(source = "following.profilePicture", target = "followingProfilePicture")
	FollowResponseDto toDto(Follow follow);
}