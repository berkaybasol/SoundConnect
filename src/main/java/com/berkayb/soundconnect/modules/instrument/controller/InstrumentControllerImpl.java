package com.berkayb.soundconnect.modules.instrument.controller;

import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentResponseDto;
import com.berkayb.soundconnect.modules.instrument.service.InstrumentService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Instrument.*;

import java.util.List;
import java.util.UUID;


@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Instrument", description = "Instrument management endpoints")
public class InstrumentControllerImpl implements InstrumentController {
	private final InstrumentService instrumentService;
	
	
	@Override
	@PostMapping(SAVE)
	//TODO preauthorize gerekli.
	public ResponseEntity<BaseResponse<InstrumentResponseDto>> saveInstrument(@Valid @RequestBody InstrumentSaveRequestDto dto) {
		InstrumentResponseDto saved = instrumentService.save(dto);
		return ResponseEntity.ok(BaseResponse.<InstrumentResponseDto>builder()
		                                     .success(true)
		                                     .message("Instrument created succesfuly.")
		                                     .code(201)
		                                     .data(saved)
		                                     .build());
	}
	
	@GetMapping(GET_ALL)
	@Override
	public ResponseEntity<BaseResponse<List<InstrumentResponseDto>>> getAllInstruments() {
		List<InstrumentResponseDto> instruments = instrumentService.findAll();
		return ResponseEntity.ok(BaseResponse.<List<InstrumentResponseDto>>builder()
				                         .success(true)
				                         .message("Instrument list retrieved succesfully")
				                         .code(200)
				                         .data(instruments)
				                         .build());
	}
	
	@Override
	@GetMapping(GET_BY_ID)
	public ResponseEntity<BaseResponse<InstrumentResponseDto>> getInstrumentById(@PathVariable UUID id) {
		InstrumentResponseDto instrument = instrumentService.findById(id);
		return ResponseEntity.ok(BaseResponse.<InstrumentResponseDto>builder()
		                                     .success(true)
		                                     .message("Instrument found.")
		                                     .code(200)
		                                     .data(instrument)
		                                     .build());
	}
	
	@Override
	@DeleteMapping(DELETE)
	//TODO preauthorize gerekli
	public ResponseEntity<BaseResponse<Void>> deleteInstrument(@PathVariable UUID id) {
		instrumentService.deleteById(id);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .message("Instrument deleted successfully.")
		                                     .code(200)
		                                     .build());
	}
}