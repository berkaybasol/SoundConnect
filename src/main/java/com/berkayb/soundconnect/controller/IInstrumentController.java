package com.berkayb.soundconnect.controller;

import com.berkayb.soundconnect.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.dto.response.BaseResponse;
import com.berkayb.soundconnect.entity.Instrument;
import org.springframework.http.ResponseEntity;

public interface IInstrumentController {
	ResponseEntity<BaseResponse<Boolean>> saveInstrument(InstrumentSaveRequestDto instrument);
}