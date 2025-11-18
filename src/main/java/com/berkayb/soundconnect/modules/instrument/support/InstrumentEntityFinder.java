package com.berkayb.soundconnect.modules.instrument.support;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class InstrumentEntityFinder {
	private final InstrumentRepository instrumentRepository;
	
	public Instrument getInstrument(UUID id) {
		return instrumentRepository.findById(id)
		                         .orElseThrow(() -> new SoundConnectException(ErrorType.INSTRUMENT_NOT_FOUND));
	}
	
	public Set<Instrument> getInstrumentsByIds(Set<UUID> ids) {
		return ids.stream()
		          .map(this::getInstrument)
		          .collect(Collectors.toSet());
	}
}