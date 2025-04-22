package com.berkayb.soundconnect.location.controller;

import com.berkayb.soundconnect.location.dto.request.CityRequestDto;
import com.berkayb.soundconnect.location.dto.response.CityPrettyDto;
import com.berkayb.soundconnect.location.dto.response.CityResponseDto;
import com.berkayb.soundconnect.location.service.CityService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.berkayb.soundconnect.shared.constant.EndPoints.City.*;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "City Controller", description = "city includes transactions")
public class CityControllerImpl implements CityController {
	private final CityService cityService;
	
	@PostMapping(SAVE)
	@Override
	public ResponseEntity<BaseResponse<CityResponseDto>> save(@RequestBody @Valid CityRequestDto dto) {
		log.info("Save city: {}", dto.name());
		CityResponseDto saved = cityService.save(dto);
		return ResponseEntity.ok(
				BaseResponse.<CityResponseDto>builder()
						.success(true)
						.message("City created succesfully")
						.code(201)
						.data(saved)
						.build()
		);
	
	}
	
	@Override
	@GetMapping(GET_ALL)
	public ResponseEntity<BaseResponse<List<CityResponseDto>>> getAll() {
		log.info("Get all cities");
		List<CityResponseDto> cities = cityService.findAll();
		return ResponseEntity.ok(
				BaseResponse.<List<CityResponseDto>>builder()
						.success(true)
						.message("All cities found")
						.code(200)
						.data(cities)
						.build()
		);
	}
	
	@Override
	@GetMapping(GET_CITY)
	public ResponseEntity<BaseResponse<CityResponseDto>> getById(@PathVariable UUID id) {
		log.info("Get city by id: {}", id);
		CityResponseDto city = cityService.findById(id);
		return ResponseEntity.ok(
				BaseResponse.<CityResponseDto>builder()
						.success(true)
						.message("city found")
						.code(200)
						.data(city)
						.build()
		);
	}
	
	@Override
	@DeleteMapping(DELETE)
	public ResponseEntity<BaseResponse<Void>> delete(@PathVariable UUID id) {
		log.info("Delete city by id: {}", id);
		cityService.delete(id);
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
						.success(true)
						.message("city deleted succesfully")
						.code(200)
						.build()
		);
	}
	
	@GetMapping(PRETTY)
	public ResponseEntity<List<CityPrettyDto>> getAllPretty() {
		log.info("Get all cities with pretty structure");
		return ResponseEntity.ok(cityService.findAllPretty());
	}
	
}