package com.berkayb.soundconnect.instrument.controller;

import com.berkayb.soundconnect.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.instrument.entity.Instrument;
import com.berkayb.soundconnect.user.service.IInstrumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.berkayb.soundconnect.shared.constant.EndPoints.INSTRUMENTS;
import static com.berkayb.soundconnect.shared.constant.EndPoints.SAVE_INSTRUMENT;

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