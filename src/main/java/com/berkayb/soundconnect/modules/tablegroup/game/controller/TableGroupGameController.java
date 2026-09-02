package com.berkayb.soundconnect.modules.tablegroup.game.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.tablegroup.chat.dto.response.TableGroupMessageResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.game.dto.request.*;
import com.berkayb.soundconnect.modules.tablegroup.game.service.TableGroupGameService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(EndPoints.TableGroup.Chat.Game.BASE)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class TableGroupGameController {
	private final TableGroupGameService gameService;

	@PostMapping
	public ResponseEntity<BaseResponse<TableGroupMessageResponseDto>> create(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID tableGroupId,
			@Valid @RequestBody TableGroupGameCreateRequestDto request
	) {
		return ResponseEntity.status(201).body(response(
				201,
				"Hesap Kimde? oyunu olusturuldu",
				gameService.create(principal.getId(), tableGroupId, request)
		));
	}

	@GetMapping(EndPoints.TableGroup.Chat.Game.ACTIVE)
	public ResponseEntity<BaseResponse<TableGroupMessageResponseDto>> active(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID tableGroupId
	) {
		TableGroupMessageResponseDto game = gameService
				.getActive(principal.getId(), tableGroupId)
				.orElse(null);
		return ResponseEntity.ok(response(200, "Aktif oyun getirildi", game));
	}

	@GetMapping(EndPoints.TableGroup.Chat.Game.BY_ID)
	public ResponseEntity<BaseResponse<TableGroupMessageResponseDto>> get(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID tableGroupId,
			@PathVariable UUID gameId
	) {
		return ResponseEntity.ok(response(
				200,
				"Oyun getirildi",
				gameService.get(principal.getId(), tableGroupId, gameId)
		));
	}

	@PostMapping(EndPoints.TableGroup.Chat.Game.JOIN)
	public ResponseEntity<BaseResponse<TableGroupMessageResponseDto>> join(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID tableGroupId,
			@PathVariable UUID gameId
	) {
		return ResponseEntity.ok(response(
				200,
				"Oyuna katildin",
				gameService.join(principal.getId(), tableGroupId, gameId)
		));
	}

	@PostMapping(EndPoints.TableGroup.Chat.Game.LEAVE)
	public ResponseEntity<BaseResponse<TableGroupMessageResponseDto>> leave(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID tableGroupId,
			@PathVariable UUID gameId
	) {
		return ResponseEntity.ok(response(
				200,
				"Oyundan ayrildin",
				gameService.leave(principal.getId(), tableGroupId, gameId)
		));
	}

	@PostMapping(EndPoints.TableGroup.Chat.Game.START)
	public ResponseEntity<BaseResponse<TableGroupMessageResponseDto>> start(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID tableGroupId,
			@PathVariable UUID gameId
	) {
		return ResponseEntity.ok(response(
				200,
				"Oyun baslatildi",
				gameService.start(principal.getId(), tableGroupId, gameId)
		));
	}

	@PostMapping(EndPoints.TableGroup.Chat.Game.CANCEL)
	public ResponseEntity<BaseResponse<TableGroupMessageResponseDto>> cancel(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID tableGroupId,
			@PathVariable UUID gameId
	) {
		return ResponseEntity.ok(response(
				200,
				"Oyun iptal edildi",
				gameService.cancel(principal.getId(), tableGroupId, gameId)
		));
	}

	@PostMapping(EndPoints.TableGroup.Chat.Game.ACTIONS)
	public ResponseEntity<BaseResponse<TableGroupMessageResponseDto>> action(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable UUID tableGroupId,
			@PathVariable UUID gameId,
			@Valid @RequestBody TableGroupGameActionRequestDto request
	) {
		return ResponseEntity.ok(response(
				200,
				"Hamlen kaydedildi",
				gameService.submitAction(principal.getId(), tableGroupId, gameId, request)
		));
	}

	private BaseResponse<TableGroupMessageResponseDto> response(
			int code,
			String message,
			TableGroupMessageResponseDto data
	) {
		return BaseResponse.<TableGroupMessageResponseDto>builder()
				.success(true)
				.code(code)
				.message(message)
				.data(data)
				.build();
	}
}
