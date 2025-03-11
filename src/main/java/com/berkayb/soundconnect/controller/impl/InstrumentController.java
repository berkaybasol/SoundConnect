package com.berkayb.soundconnect.controller.impl;

import com.berkayb.soundconnect.controller.IInstrumentController;
import com.berkayb.soundconnect.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.dto.response.BaseResponse;
import com.berkayb.soundconnect.entity.Instrument;
import com.berkayb.soundconnect.repository.InstrumentRepository;
import com.berkayb.soundconnect.service.IInstrumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.berkayb.soundconnect.constant.EndPoints.INSTRUMENTS;
import static com.berkayb.soundconnect.constant.EndPoints.SAVE_INSTRUMENT;

@RestController
@RequestMapping(INSTRUMENTS)
@RequiredArgsConstructor
public class InstrumentController implements IInstrumentController {
	private final IInstrumentService instrumentService;
	
	@PostMapping(SAVE_INSTRUMENT)
	@Override
	public ResponseEntity<BaseResponse<Boolean>> saveInstrument(@RequestBody InstrumentSaveRequestDto instrument) {
		Instrument savedInstrument = instrumentService.saveInstrument(instrument);
		return ResponseEntity.ok(BaseResponse.<Boolean>builder()
				                         .code(200)
				                         .data(true)
				                         .message("Enstruman kaydedildi. Enstruman Id: " + savedInstrument.getId())
				                         .success(true)
				                         .build());
		
	}
}