package com.berkayb.soundconnect.instrument.controller;

import com.berkayb.soundconnect.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

public interface InstrumentController {
	ResponseEntity<BaseResponse<Boolean>> saveInstrument(InstrumentSaveRequestDto instrument);
}