package com.berkayb.soundconnect.modules.instrument.controller.user;

import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentResponseDto;
import com.berkayb.soundconnect.modules.instrument.service.InstrumentService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.InstrumentEndpoints.*;

@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Tag(name = "FOR USERS / Instruments", description = "Instrument endpoints for users")
public class InstrumentUserController {
	private final InstrumentService instrumentService;
	
	@GetMapping(LIST)
	public ResponseEntity<BaseResponse<List<InstrumentResponseDto>>> getAllInstruments() {
		List<InstrumentResponseDto> instruments = instrumentService.findAll();
		return ResponseEntity.ok(BaseResponse.<List<InstrumentResponseDto>>builder()
		                                     .success(true)
		                                     .message("Instrument list retrieved successfully")
		                                     .code(200)
		                                     .data(instruments)
		                                     .build());
	}
	
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
}