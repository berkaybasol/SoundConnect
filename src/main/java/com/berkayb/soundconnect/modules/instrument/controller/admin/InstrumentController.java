package com.berkayb.soundconnect.modules.instrument.controller.admin;

import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import org.springframework.http.ResponseEntity;

public interface InstrumentController {
	ResponseEntity<BaseResponse<Boolean>> saveInstrument(InstrumentSaveRequestDto instrument);
}