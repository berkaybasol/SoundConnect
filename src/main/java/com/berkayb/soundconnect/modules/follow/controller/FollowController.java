package com.berkayb.soundconnect.modules.follow.controller;


import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.follow.dto.request.FollowRequestDto;
import com.berkayb.soundconnect.modules.follow.dto.response.FollowResponseDto;
import com.berkayb.soundconnect.modules.follow.entity.Follow;
import com.berkayb.soundconnect.modules.follow.mapper.FollowMapper;
import com.berkayb.soundconnect.modules.follow.service.FollowService;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Follow.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Slf4j
public class FollowController {
	private final FollowService followService;
	private final UserEntityFinder userEntityFinder;
	private final FollowMapper followMapper;
	private final GhostListenerIdentityBatchResolver ghostIdentityBatchResolver;
	
	@GetMapping(FOLLOWERS_COUNT)
	@PreAuthorize("isAuthenticated()")
	public BaseResponse<Long> countFollowers(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID userId
	) {
		UUID viewerId = requireAuthenticatedUserId(userDetails);
		User following = userEntityFinder.getUser(userId);
		long count = followService.countFollowersVisibleTo(viewerId, following);
		
		return BaseResponse.<Long>builder()
		                   .success(true)
		                   .data(count)
		                   .code(200)
		                   .message("Followers count fetched successfully.")
		                   .build();
	}
	
	@GetMapping(FOLLOWING_COUNT)
	@PreAuthorize("isAuthenticated()")
	public BaseResponse<Long> countFollowing(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID userId
	) {
		UUID viewerId = requireAuthenticatedUserId(userDetails);
		User follower = userEntityFinder.getUser(userId);
		long count = followService.countFollowingVisibleTo(viewerId, follower);
		return BaseResponse.<Long>builder()
		                   .success(true)
		                   .data(count)
		                   .code(200)
		                   .message("Following count fetched successfully.")
		                   .build();
	}
	
	@PostMapping(FOLLOW)
	@PreAuthorize("isAuthenticated()")
	public BaseResponse<Void> follow(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestBody @Valid FollowRequestDto requestDto
	) {
		UUID followerId = userDetails.getUser().getId();
		log.info("Follow request: followerId={} followingId={}", followerId, requestDto.followingId());
		
		User follower = userEntityFinder.getUser(followerId);
		User following = userEntityFinder.getUser(requestDto.followingId());
		
		followService.follow(follower, following);
		
		// TODO: Notification modülü eklendiğinde burada async notification tetiklenecek.
		
		return BaseResponse.<Void>builder()
		                   .success(true)
		                   .message("User followed successfully.")
		                   .code(200)
		                   .build();
	}
	
	
	@PostMapping(UNFOLLOW)
	@PreAuthorize("isAuthenticated()")
	public BaseResponse<Void> unfollow(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestBody @Valid FollowRequestDto requestDto
	) {
		UUID followerId = userDetails.getUser().getId();
		log.info("Unfollow request: followerId={} followingId={}", followerId, requestDto.followingId());
		
		User follower = userEntityFinder.getUser(followerId);
		User following = userEntityFinder.getUser(requestDto.followingId());
		
		followService.unfollow(follower, following);
		
		// TODO: Notification modülü eklendiğinde burada async notification tetiklenecek.
		
		return BaseResponse.<Void>builder()
		                   .success(true)
		                   .message("User unfollowed successfully.")
		                   .code(200)
		                   .build();
	}
	
	@Transactional
	@GetMapping(GET_FOLLOWING)
	@PreAuthorize("isAuthenticated()")
	public BaseResponse<List<FollowResponseDto>> getFollowing(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID userId
	) {
		UUID viewerId = requireAuthenticatedUserId(userDetails);
		User follower = userEntityFinder.getUser(userId);
		List<Follow> follows =
				followService.getFollowingVisibleTo(viewerId, follower);
		Map<UUID, GhostListenerIdentity> ghostIdentities = resolveGhostIdentities(follows);
		List<FollowResponseDto> dtoList = follows
		                                               .stream()
		                                               .map(follow -> followMapper.toDto(follow, ghostIdentities))
		                                               .collect(Collectors.toList());
		
		return BaseResponse.<List<FollowResponseDto>>builder()
		                   .success(true)
		                   .data(dtoList)
		                   .code(200)
		                   .message("Following list fetched successfully.")
		                   .build();
	}
	
	@Transactional
	@GetMapping(GET_FOLLOWERS)
	@PreAuthorize("isAuthenticated()")
	public BaseResponse<List<FollowResponseDto>> getFollowers(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID userId
	) {
		UUID viewerId = requireAuthenticatedUserId(userDetails);
		User following = userEntityFinder.getUser(userId);
		List<Follow> follows =
				followService.getFollowersVisibleTo(viewerId, following);
		Map<UUID, GhostListenerIdentity> ghostIdentities = resolveGhostIdentities(follows);
		List<FollowResponseDto> dtoList = follows
		                                               .stream()
		                                               .map(follow -> followMapper.toDto(follow, ghostIdentities))
		                                               .collect(Collectors.toList());
		
		return BaseResponse.<List<FollowResponseDto>>builder()
		                   .success(true)
		                   .data(dtoList)
		                   .code(200)
		                   .message("Followers list fetched successfully.")
		                   .build();
	}

	@GetMapping(IS_FOLLOWING)
	@PreAuthorize("isAuthenticated()")
	public BaseResponse<Boolean> isFollowing(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestParam(required = false) UUID followerId,
			@RequestParam UUID followingId
	) {
		UUID viewerId = requireAuthenticatedUserId(userDetails);
		if (followerId != null && !viewerId.equals(followerId)) {
			throw new SoundConnectException(ErrorType.FOLLOW_RELATION_QUERY_FORBIDDEN);
		}

		User viewer = userEntityFinder.getUser(viewerId);
		User following = userEntityFinder.getUser(followingId);
		
		boolean result = followService.isFollowingVisibleTo(viewer, followerId, following);
		
		return BaseResponse.<Boolean>builder()
		                   .success(true)
		                   .data(result)
		                   .code(200)
		                   .message("Is following status fetched successfully.")
		                   .build();
	}

	private UUID requireAuthenticatedUserId(UserDetailsImpl userDetails) {
		if (userDetails == null || userDetails.getUser() == null || userDetails.getUser().getId() == null) {
			throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		}
		return userDetails.getUser().getId();
	}

	private Map<UUID, GhostListenerIdentity> resolveGhostIdentities(
			List<Follow> follows
	) {
		if (follows == null || follows.isEmpty()) {
			return Map.of();
		}
		LinkedHashSet<UUID> userIds = new LinkedHashSet<>();
		for (Follow follow : follows) {
			if (follow == null) continue;
			if (follow.getFollower() != null && follow.getFollower().getId() != null) {
				userIds.add(follow.getFollower().getId());
			}
			if (follow.getFollowing() != null && follow.getFollowing().getId() != null) {
				userIds.add(follow.getFollowing().getId());
			}
		}
		if (userIds.isEmpty()) {
			return Map.of();
		}
		return ghostIdentityBatchResolver.resolve(userIds);
	}
}
