package com.berkayb.soundconnect.modules.event.performer.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerRequestResponseDto;
import com.berkayb.soundconnect.modules.event.performer.dto.EventPerformerAcceptRequestDto;
import com.berkayb.soundconnect.modules.event.performer.enums.EventPerformerRequestStatus;
import com.berkayb.soundconnect.modules.event.performer.service.EventPerformerRequestService;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/event-performer-requests")
@RequiredArgsConstructor
@Tag(name = "FOR MUSICIANS / Event Performer Requests")
public class EventPerformerRequestController {

	private final EventPerformerRequestService service;

	@PreAuthorize("hasRole('MUSICIAN')")
	@GetMapping("/mine")
	public ResponseEntity<BaseResponse<PageResponse<EventPerformerRequestResponseDto>>> getMine(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestParam(required = false) EventPerformerRequestStatus status,
			@RequestParam(required = false) PerformerType targetType,
			@RequestParam(required = false) UUID targetId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size
	) {
		validateTargetScope(targetType, targetId);
		var response = service.getMine(
				userDetails.getUser().getId(),
				status,
				targetType,
				targetId,
				page,
				size
		);
		return ResponseEntity.ok().header("Cache-Control", "no-store, private").body(BaseResponse.<PageResponse<EventPerformerRequestResponseDto>>builder()
				.success(true)
				.code(200)
				.message("Etkinlik katılım istekleri getirildi.")
				.data(response)
				.build());
	}

	private void validateTargetScope(PerformerType targetType, UUID targetId) {
		if ((targetType == null) != (targetId == null) || targetType == PerformerType.MANUAL) {
			throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		}
	}

	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping("/{requestId}/accept")
	public ResponseEntity<BaseResponse<EventPerformerRequestResponseDto>> accept(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID requestId,
			@RequestBody(required = false) EventPerformerAcceptRequestDto body
	) {
		return decisionResponse(service.accept(userDetails.getUser().getId(), requestId,
				body == null ? null : body.showOnProfile()), "Etkinlik katılımı onaylandı.");
	}

	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping("/{requestId}/reconsider")
	public ResponseEntity<BaseResponse<EventPerformerRequestResponseDto>> reconsider(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID requestId,
			@RequestBody EventPerformerAcceptRequestDto body
	) {
		if (body.showOnProfile() == null) throw new SoundConnectException(ErrorType.INVALID_PARAMETER);
		return decisionResponse(service.reconsider(userDetails.getUser().getId(), requestId,
				body.showOnProfile()), "Etkinlik daveti onaylandı.");
	}

	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping("/{requestId}/reject")
	public ResponseEntity<BaseResponse<EventPerformerRequestResponseDto>> reject(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID requestId
	) {
		return decisionResponse(service.reject(userDetails.getUser().getId(), requestId), "Etkinlik katılımı reddedildi.");
	}

	private ResponseEntity<BaseResponse<EventPerformerRequestResponseDto>> decisionResponse(
			EventPerformerRequestResponseDto response,
			String message
	) {
		return ResponseEntity.ok().header("Cache-Control", "no-store, private").body(BaseResponse.<EventPerformerRequestResponseDto>builder()
				.success(true)
				.code(200)
				.message(message)
				.data(response)
				.build());
	}
}
