package com.berkayb.soundconnect.modules.instrument.mapper;

import com.berkayb.soundconnect.modules.instrument.dto.response.InstrumentDto;
import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface InstrumentMapper {
	
	@Mapping(target = "id", ignore = true)
	@Mapping(target = "user", source = "user")
	Instrument toInstrument(InstrumentSaveRequestDto dto, User user);
	
	InstrumentDto toResponse(Instrument instrument);
	List<InstrumentDto> toResponseList(List<Instrument> instruments);
}