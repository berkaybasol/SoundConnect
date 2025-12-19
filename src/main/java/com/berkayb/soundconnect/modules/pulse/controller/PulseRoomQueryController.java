package com.berkayb.soundconnect.modules.pulse.controller;

import com.berkayb.soundconnect.modules.pulse.dto.response.PulseRoomResponseDto;
import com.berkayb.soundconnect.modules.pulse.model.PulseRoomState;
import com.berkayb.soundconnect.modules.pulse.redis.PulseRedisService;
import com.berkayb.soundconnect.modules.pulse.registry.PulseRoomRegistry;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/pulse/rooms")
@RequiredArgsConstructor
@Slf4j
public class PulseRoomQueryController {
	
	private final PulseRoomRegistry pulseRoomRegistry;
	private final PulseRedisService pulseRedisService;
	
	@GetMapping
	public ResponseEntity<BaseResponse<List<PulseRoomResponseDto>>> getAllRooms() {
		
		List<PulseRoomResponseDto> rooms = pulseRoomRegistry.getAllRoomIds()
		                                                    .stream()
		                                                    .map(this::mapToResponseStrict) // strict: null yok
		                                                    .sorted(Comparator.comparing(
				                                                    PulseRoomResponseDto::roomNumber,
				                                                    Comparator.nullsLast(Integer::compareTo)
		                                                    ))
		                                                    .toList();
		
		log.debug("[PulseQuery] Rooms fetched. count={}", rooms.size());
		
		return ResponseEntity.ok(
				BaseResponse.<List<PulseRoomResponseDto>>builder()
				            .success(true)
				            .message("Pulse rooms fetched successfully")
				            .code(200)
				            .data(rooms)
				            .build()
		);
	}
	
	/**
	 * Registry'de olan her oda Redis'te de olmak zorunda.
	 * Yoksa bu sistemsel inconsistency'dir: silently null dönmek yerine log + skip yapmıyoruz,
	 * direkt fail-fast yapıyoruz ki bug saklanmasın.
	 */
	private PulseRoomResponseDto mapToResponseStrict(UUID roomId) {
		PulseRoomState state = pulseRedisService.getRoomState(roomId)
		                                        .orElseThrow(() -> {
			                                        log.error("[PulseQuery] Registry contains room but Redis missing. roomId={}", roomId);
			                                        return new IllegalStateException("Pulse room registry/redis inconsistency. roomId=" + roomId);
		                                        });
		
		return new PulseRoomResponseDto(
				state.getRoomId(),
				state.getRoomNumber(),
				state.getTopic(),
				state.getEndsAt(),
				state.getActiveUserCount(),
				state.getStatus()
		);
	}
}