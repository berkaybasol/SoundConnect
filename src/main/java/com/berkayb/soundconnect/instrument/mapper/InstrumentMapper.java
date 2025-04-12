package com.berkayb.soundconnect.instrument.mapper;

import com.berkayb.soundconnect.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.instrument.entity.Instrument;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InstrumentMapper {
	@Mapping(target = "id", ignore = true)
	Instrument toInstrument(InstrumentSaveRequestDto dto);
	
}