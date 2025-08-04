package com.berkayb.soundconnect.modules.location.controller;

import com.berkayb.soundconnect.modules.location.dto.request.DistrictRequestDto;
import com.berkayb.soundconnect.modules.location.dto.response.DistrictResponseDto;
import com.berkayb.soundconnect.modules.location.service.DistrictService;
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

import static com.berkayb.soundconnect.shared.constant.EndPoints.District.*;

@Slf4j
@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "FOR ADMIN / District", description = "district includes transactions")
public class DistrictControllerImpl implements DistrictController {
	private final DistrictService districtService;
	
	@PreAuthorize("hasAuthority('WRITE_LOCATION')")
	@PostMapping(SAVE)
	@Override
	public ResponseEntity<BaseResponse<DistrictResponseDto>> save(@RequestBody @Valid DistrictRequestDto dto) {
		log.info("save district request: {}", dto.name());
		DistrictResponseDto saved = districtService.save(dto);
		return ResponseEntity.ok(
				BaseResponse.<DistrictResponseDto>builder()
				            .success(true)
				            .message("district created successfully")
				            .code(201)
				            .data(saved)
				            .build()
		);
	}
	
	@PreAuthorize("hasAuthority('READ_LOCATION')")
	@GetMapping(GET_ALL)
	@Override
	public ResponseEntity<BaseResponse<List<DistrictResponseDto>>> getAll() {
		log.info("get all districts");
		List<DistrictResponseDto> districts = districtService.findAll();
		return ResponseEntity.ok(
				BaseResponse.<List<DistrictResponseDto>>builder()
						.success(true)
						.message("all district retrieved succesfully")
						.code(200)
						.data(districts)
						.build()
		);
	}
	
	@PreAuthorize("hasAuthority('READ_LOCATION')")
	@GetMapping(GET_BY_ID)
	@Override
	public ResponseEntity<BaseResponse<DistrictResponseDto>> getById(@PathVariable UUID id) {
		log.info("Get district by id: {}", id);
		DistrictResponseDto district = districtService.findById(id);
		return ResponseEntity.ok(
				BaseResponse.<DistrictResponseDto>builder()
				            .success(true)
				            .message("District retrieved successfully")
				            .code(200)
				            .data(district)
				            .build()
		);
	}
	
	@PreAuthorize("hasAuthority('READ_LOCATION')")
	@GetMapping(GET_BY_CITY)
	@Override
	public ResponseEntity<BaseResponse<List<DistrictResponseDto>>> getByCityId(@PathVariable UUID cityId) {
		log.info("Get districts by city id: {}", cityId);
		List<DistrictResponseDto> districts = districtService.findByCityId(cityId);
		return ResponseEntity.ok(
				BaseResponse.<List<DistrictResponseDto>>builder()
				            .success(true)
				            .message("Districts by city retrieved successfully")
				            .code(200)
				            .data(districts)
				            .build()
		);
	}
	
	@PreAuthorize("hasAuthority('DELETE_LOCATION')")
	@DeleteMapping(DELETE)
	@Override
	public ResponseEntity<BaseResponse<Void>> delete(@PathVariable UUID id) {
		log.info("Delete district by id: {}", id);
		districtService.delete(id);
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
				            .success(true)
				            .message("District deleted successfully")
				            .code(200)
				            .build()
		);
	}
}