package com.berkayb.soundconnect.mapper;

import com.berkayb.soundconnect.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.entity.Instrument;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface InstrumentMapper {
	@Mapping(target = "id", ignore = true)
	Instrument toInstrument(InstrumentSaveRequestDto dto);
	
}