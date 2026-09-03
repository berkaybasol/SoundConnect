package com.berkayb.soundconnect.modules.follow.mapper;

import com.berkayb.soundconnect.modules.follow.dto.response.FollowResponseDto;
import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Map;
import java.util.UUID;

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
	@Mapping(target = "followerVisibilityMode", ignore = true)
	@Mapping(target = "followingVisibilityMode", ignore = true)
	FollowResponseDto toDto(Follow follow);

	default FollowResponseDto toDto(
			Follow follow,
			Map<UUID, GhostListenerIdentity> ghostIdentities
	) {
		FollowResponseDto base = toDto(follow);
		Map<UUID, GhostListenerIdentity> safeIdentities = ghostIdentities == null
				? Map.of()
				: ghostIdentities;
		GhostListenerIdentity followerIdentity = safeIdentities.get(base.followerId());
		GhostListenerIdentity followingIdentity = safeIdentities.get(base.followingId());

		return new FollowResponseDto(
				base.id(),
				base.followerId(),
				followerIdentity == null ? base.followerUsername() : followerIdentity.username(),
				followerIdentity == null ? base.followerProfilePicture() : followerIdentity.profilePictureUrl(),
				followerIdentity == null ? null : followerIdentity.visibilityMode(),
				base.followingId(),
				followingIdentity == null ? base.followingUsername() : followingIdentity.username(),
				followingIdentity == null ? base.followingProfilePicture() : followingIdentity.profilePictureUrl(),
				followingIdentity == null ? null : followingIdentity.visibilityMode(),
				base.followedAt()
		);
	}
}
