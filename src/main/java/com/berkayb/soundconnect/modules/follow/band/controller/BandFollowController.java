package com.berkayb.soundconnect.modules.follow.band.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.follow.band.dto.response.BandFollowResponseDto;
import com.berkayb.soundconnect.modules.follow.band.service.BandFollowService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.BandFollow.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "Band Follow", description = "Band takip işlemleri")
public class BandFollowController {
	
	private final BandFollowService bandFollowService;
	
	@PostMapping(FOLLOW)
	@Operation(summary = "Login olan kullanıcı band'i takip eder")
	public ResponseEntity<BaseResponse<Void>> followBand(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId
	) {
		bandFollowService.followBand(userDetails.getUser().getId(), bandId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .code(200)
				            .message("Band followed successfully.")
				            .build()
		);
	}
	
	@DeleteMapping(UNFOLLOW)
	@Operation(summary = "Login olan kullanıcı band'i takipten çıkarır")
	public ResponseEntity<BaseResponse<Void>> unfollowBand(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId
	) {
		bandFollowService.unfollowBand(userDetails.getUser().getId(), bandId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .code(200)
				            .message("Band unfollowed successfully.")
				            .build()
		);
	}
	
	@GetMapping(IS_FOLLOWING)
	@Operation(summary = "Login olan kullanıcı bu band'i takip ediyor mu")
	public ResponseEntity<BaseResponse<Boolean>> isFollowingBand(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID bandId
	) {
		boolean result = bandFollowService.isFollowingBand(userDetails.getUser().getId(), bandId);
		
		return ResponseEntity.ok(
				BaseResponse.<Boolean>builder()
				            .success(true)
				            .code(200)
				            .message("Band following status fetched successfully.")
				            .data(result)
				            .build()
		);
	}
	
	@GetMapping(FOLLOWER_COUNT)
	@Operation(summary = "Band follower count getirir")
	public ResponseEntity<BaseResponse<Long>> countBandFollowers(@PathVariable UUID bandId) {
		long count = bandFollowService.countFollowers(bandId);
		
		return ResponseEntity.ok(
				BaseResponse.<Long>builder()
				            .success(true)
				            .code(200)
				            .message("Band follower count fetched successfully.")
				            .data(count)
				            .build()
		);
	}
	
	@GetMapping(FOLLOWERS)
	@Operation(summary = "Band follower listesini getirir")
	public ResponseEntity<BaseResponse<List<BandFollowResponseDto>>> getBandFollowers(@PathVariable UUID bandId) {
		List<BandFollowResponseDto> followers = bandFollowService.getBandFollowers(bandId);
		
		return ResponseEntity.ok(
				BaseResponse.<List<BandFollowResponseDto>>builder()
				            .success(true)
				            .code(200)
				            .message("Band followers fetched successfully.")
				            .data(followers)
				            .build()
		);
	}
	
	@GetMapping(MY_FOLLOWED_BANDS)
	@Operation(summary = "Login olan kullanıcının takip ettiği bandleri getirir")
	public ResponseEntity<BaseResponse<List<BandFollowResponseDto>>> getMyFollowedBands(
			@AuthenticationPrincipal UserDetailsImpl userDetails
	) {
		List<BandFollowResponseDto> followedBands = bandFollowService.getMyFollowedBands(userDetails.getUser().getId());
		
		return ResponseEntity.ok(
				BaseResponse.<List<BandFollowResponseDto>>builder()
				            .success(true)
				            .code(200)
				            .message("Followed bands fetched successfully.")
				            .data(followedBands)
				            .build()
		);
	}
}