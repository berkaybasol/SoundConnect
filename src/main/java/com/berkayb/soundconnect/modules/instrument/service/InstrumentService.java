package com.berkayb.soundconnect.modules.instrument.service;


import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentResponseDto;


import java.util.List;
import java.util.UUID;

public interface InstrumentService {
	
	// admin
	InstrumentResponseDto save(InstrumentSaveRequestDto dto);
	
	List<InstrumentResponseDto> findAll();
	
	InstrumentResponseDto findById(UUID id);
	
	//admin
	void deleteById(UUID id);
	
}