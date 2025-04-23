package com.berkayb.soundconnect.modules.instrument.service;

import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.mapper.InstrumentMapper;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InstrumentServiceImpl implements IInstrumentService {
	private final InstrumentRepository instrumentRepository;
	private final InstrumentMapper instrumentMapper;
	private final UserEntityFinder userEntityFinder;
	
	
	@Override
	public Instrument saveInstrument(InstrumentSaveRequestDto dto) {
		User user = userEntityFinder.getUser(dto.userId());
		Instrument instrument = instrumentMapper.toInstrument(dto, user);
		return instrumentRepository.save(instrument);
	}
	
	
	
}