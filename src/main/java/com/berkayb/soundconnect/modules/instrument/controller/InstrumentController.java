package com.berkayb.soundconnect.modules.instrument.controller;

import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentResponseDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

public interface InstrumentController {
	
	
	ResponseEntity<BaseResponse<InstrumentResponseDto>> saveInstrument(InstrumentSaveRequestDto dto);
	ResponseEntity<BaseResponse<List<InstrumentResponseDto>>> getAllInstruments();
	ResponseEntity<BaseResponse<InstrumentResponseDto>> getInstrumentById(UUID id);
	ResponseEntity<BaseResponse<Void>> deleteInstrument(UUID id);
	
}