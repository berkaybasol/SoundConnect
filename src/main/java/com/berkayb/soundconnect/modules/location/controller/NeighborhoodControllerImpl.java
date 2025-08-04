package com.berkayb.soundconnect.modules.location.controller;

import com.berkayb.soundconnect.modules.location.dto.request.NeighborhoodRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.NeighborhoodResponseDto;
import com.berkayb.soundconnect.modules.location.service.NeighborhoodService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Neighborhood.*;

@Slf4j
@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "FOR ADMIN / Neighboor", description = "Neighborhood management endpoints")
public class NeighborhoodControllerImpl implements NeighborhoodController {
	
	private final NeighborhoodService neighborhoodService;
	
	@PreAuthorize("hasAuthority('WRITE_LOCATION')")
	@PostMapping(SAVE)
	@Override
	public ResponseEntity<BaseResponse<NeighborhoodResponseDto>> save(@RequestBody @Valid NeighborhoodRequestDto dto) {
		log.info("Save neighborhood request: {}", dto.name());
		NeighborhoodResponseDto saved = neighborhoodService.save(dto);
		return ResponseEntity.ok(
				BaseResponse.<NeighborhoodResponseDto>builder()
				            .success(true)
				            .message("Neighborhood created successfully")
				            .code(201)
				            .data(saved)
				            .build()
		);
	}
	
	@PreAuthorize("hasAuthority('READ_LOCATION')")
	@GetMapping(GET_ALL)
	@Override
	public ResponseEntity<BaseResponse<List<NeighborhoodResponseDto>>> getAll() {
		log.info("Get all neighborhoods");
		List<NeighborhoodResponseDto> neighborhoods = neighborhoodService.findAll();
		return ResponseEntity.ok(
				BaseResponse.<List<NeighborhoodResponseDto>>builder()
				            .success(true)
				            .message("All neighborhoods retrieved successfully")
				            .code(200)
				            .data(neighborhoods)
				            .build()
		);
	}
	
	@PreAuthorize("hasAuthority('READ_LOCATION')")
	@GetMapping(GET_BY_ID)
	@Override
	public ResponseEntity<BaseResponse<NeighborhoodResponseDto>> getById(@PathVariable UUID id) {
		log.info("Get neighborhood by id: {}", id);
		NeighborhoodResponseDto neighborhood = neighborhoodService.findById(id);
		return ResponseEntity.ok(
				BaseResponse.<NeighborhoodResponseDto>builder()
				            .success(true)
				            .message("Neighborhood retrieved successfully")
				            .code(200)
				            .data(neighborhood)
				            .build()
		);
	}
	
	@PreAuthorize("hasAuthority('READ_LOCATION')")
	@GetMapping(GET_BY_DISTRICT)
	@Override
	public ResponseEntity<BaseResponse<List<NeighborhoodResponseDto>>> getByDistrictId(@PathVariable UUID districtId) {
		log.info("Get neighborhoods by district id: {}", districtId);
		List<NeighborhoodResponseDto> neighborhoods = neighborhoodService.findByDistrictId(districtId);
		return ResponseEntity.ok(
				BaseResponse.<List<NeighborhoodResponseDto>>builder()
				            .success(true)
				            .message("Neighborhoods by district retrieved successfully")
				            .code(200)
				            .data(neighborhoods)
				            .build()
		);
	}
	
	@PreAuthorize("hasAuthority('DELETE_LOCATION')")
	@DeleteMapping(DELETE)
	@Override
	public ResponseEntity<BaseResponse<Void>> delete(@PathVariable UUID id) {
		log.info("Delete neighborhood by id: {}", id);
		neighborhoodService.delete(id);
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .message("Neighborhood deleted successfully")
				            .code(200)
				            .build()
		);
	}
}