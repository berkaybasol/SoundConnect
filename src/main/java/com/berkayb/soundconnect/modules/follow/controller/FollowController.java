package com.berkayb.soundconnect.modules.follow.controller;


import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.follow.dto.request.FollowRequestDto;
import com.berkayb.soundconnect.modules.follow.dto.response.FollowResponseDto;
import com.berkayb.soundconnect.modules.follow.mapper.FollowMapper;
import com.berkayb.soundconnect.modules.follow.service.FollowService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
	
	@GetMapping(FOLLOWERS_COUNT)
	public BaseResponse<Long> countFollowers(@PathVariable UUID userId) {
		User following = userEntityFinder.getUser(userId);
		long count = followService.countFollowers(following);
		
		return BaseResponse.<Long>builder()
		                   .success(true)
		                   .data(count)
		                   .code(200)
		                   .message("Followers count fetched successfully.")
		                   .build();
	}
	
	@GetMapping(FOLLOWING_COUNT)
	public BaseResponse<Long> countFollowing(@PathVariable UUID userId) {
		User follower = userEntityFinder.getUser(userId);
		long count = followService.countFollowing(follower);
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
	
	@Transactional(readOnly = true)
	@GetMapping(GET_FOLLOWING)
	public BaseResponse<List<FollowResponseDto>> getFollowing(@PathVariable UUID userId) {
		User follower = userEntityFinder.getUser(userId);
		List<FollowResponseDto> dtoList = followService.getFollowing(follower)
		                                               .stream()
		                                               .map(followMapper::toDto)
		                                               .collect(Collectors.toList());
		
		return BaseResponse.<List<FollowResponseDto>>builder()
		                   .success(true)
		                   .data(dtoList)
		                   .code(200)
		                   .message("Following list fetched successfully.")
		                   .build();
	}
	
	@Transactional(readOnly = true)
	@GetMapping(GET_FOLLOWERS)
	public BaseResponse<List<FollowResponseDto>> getFollowers(@PathVariable UUID userId) {
		User following = userEntityFinder.getUser(userId);
		List<FollowResponseDto> dtoList = followService.getFollowers(following)
		                                               .stream()
		                                               .map(followMapper::toDto)
		                                               .collect(Collectors.toList());
		
		return BaseResponse.<List<FollowResponseDto>>builder()
		                   .success(true)
		                   .data(dtoList)
		                   .code(200)
		                   .message("Followers list fetched successfully.")
		                   .build();
	}

	@GetMapping(IS_FOLLOWING)
	public BaseResponse<Boolean> isFollowing(@RequestParam UUID followerId, @RequestParam UUID followingId) {
		User follower = userEntityFinder.getUser(followerId);
		User following = userEntityFinder.getUser(followingId);
		
		boolean result = followService.isFollowing(follower, following);
		
		return BaseResponse.<Boolean>builder()
		                   .success(true)
		                   .data(result)
		                   .code(200)
		                   .message("Is following status fetched successfully.")
		                   .build();
	}
}
