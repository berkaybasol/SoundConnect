package com.berkayb.soundconnect.modules.instrument.service;


import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentResponseDto;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.instrument.mapper.InstrumentMapper;
import com.berkayb.soundconnect.modules.instrument.repository.InstrumentRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InstrumentServiceImpl implements InstrumentService {
	private final InstrumentRepository instrumentRepository;
	private final InstrumentMapper instrumentMapper;
	
	
	@Override
	public InstrumentResponseDto save(InstrumentSaveRequestDto dto) {
		if (instrumentRepository.existsByName(dto.name())) {
			log.warn("Instrument with name {} already exists", dto.name());
			throw new SoundConnectException(ErrorType.INSTRUMENT_ALREADY_EXISTS);
		}
		Instrument instrument = instrumentMapper.toInstrument(dto);
		Instrument saved = instrumentRepository.save(instrument);
		log.info("Instrument saved: {}", saved.getName());
		return instrumentMapper.toInstrumentResponseDto(saved);
	}
	
	@Override
	public List<InstrumentResponseDto> findAll() {
		List<Instrument> instruments = instrumentRepository.findAll();
		log.info("Instrument list requested, count: {}", instruments.size());
		return instruments.stream()
				.map(instrumentMapper::toInstrumentResponseDto)
				.collect(Collectors.toList());
	}
	
	@Override
	public InstrumentResponseDto findById(UUID id) {
		Instrument instrument = instrumentRepository.findById(id)
		.orElseThrow(() -> {
		log.warn("instrument not found with id {}", id);
		return new SoundConnectException(ErrorType.INSTRUMENT_NOT_FOUND);
		 });
		log.info("Instrument found: {}", instrument.getName());
		return instrumentMapper.toInstrumentResponseDto(instrument);
	}
	
	
	@Override
	public void deleteById(UUID id) {
	if (!instrumentRepository.existsById(id)) {
		log.warn("tried to delete non-existing instrument with id {}", id);
		throw new SoundConnectException(ErrorType.INSTRUMENT_NOT_FOUND);
		}
	instrumentRepository.deleteById(id);
	log.info("Instrument deleted: {}", id);
	}
}