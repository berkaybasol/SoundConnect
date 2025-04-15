package com.berkayb.soundconnect.instrument.service;

import com.berkayb.soundconnect.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.instrument.entity.Instrument;
import com.berkayb.soundconnect.instrument.mapper.InstrumentMapper;
import com.berkayb.soundconnect.instrument.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InstrumentServiceImpl implements IInstrumentService {
	private final InstrumentRepository instrumentRepository;
	private final InstrumentMapper instrumentMapper;
	
	
	@Override
	public Instrument saveInstrument(InstrumentSaveRequestDto instrument) {
		return instrumentRepository.save(instrumentMapper.toInstrument(instrument));
	}
	
	
}