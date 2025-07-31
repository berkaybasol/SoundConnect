package com.berkayb.soundconnect.modules.instrument.mapper;


import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentResponseDto;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface InstrumentMapper {
	
	InstrumentMapper INSTANCE = Mappers.getMapper(InstrumentMapper.class);
	
	Instrument toInstrument(InstrumentSaveRequestDto instrument);
	
	@Mapping(source = "id", target = "id")
	@Mapping(source = "name", target = "name")
	InstrumentResponseDto toInstrumentResponseDto(Instrument instrument);
}