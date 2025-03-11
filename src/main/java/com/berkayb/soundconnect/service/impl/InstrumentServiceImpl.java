package com.berkayb.soundconnect.service.impl;

import com.berkayb.soundconnect.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.entity.Instrument;
import com.berkayb.soundconnect.mapper.InstrumentMapper;
import com.berkayb.soundconnect.repository.InstrumentRepository;
import com.berkayb.soundconnect.service.IInstrumentService;
import com.berkayb.soundconnect.service.IUserService;
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