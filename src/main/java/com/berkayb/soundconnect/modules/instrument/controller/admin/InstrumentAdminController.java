package com.berkayb.soundconnect.modules.instrument.controller.admin;

import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentResponseDto;
import com.berkayb.soundconnect.modules.instrument.service.InstrumentService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.InstrumentEndpoints.*;

@RestController
@RequestMapping(ADMIN_BASE)
@RequiredArgsConstructor
@Tag(name = "Instrument - Admin", description = "Instrument management endpoints for admin")
public class InstrumentAdminController {
	private final InstrumentService instrumentService;
	
	//TODO IZIN
	@PostMapping(CREATE)
	public ResponseEntity<BaseResponse<InstrumentResponseDto>> saveInstrument(@Valid @RequestBody InstrumentSaveRequestDto dto) {
		InstrumentResponseDto saved = instrumentService.save(dto);
		return ResponseEntity.ok(BaseResponse.<InstrumentResponseDto>builder()
		                                     .success(true)
		                                     .message("Instrument created successfully.")
		                                     .code(201)
		                                     .data(saved)
		                                     .build());
	}
	
	//TODO IZIN
	@DeleteMapping(DELETE)
	public ResponseEntity<BaseResponse<Void>> deleteInstrument(@PathVariable UUID id) {
		instrumentService.deleteById(id);
		return ResponseEntity.ok(BaseResponse.<Void>builder()
		                                     .success(true)
		                                     .message("Instrument deleted successfully.")
		                                     .code(200)
		                                     .build());
	}
}