package com.berkayb.soundconnect.modules.setlistcreator.mapper;

import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistCreateRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.response.SetlistResponseDto;
import com.berkayb.soundconnect.modules.setlistcreator.entity.Setlist;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Setlist <-> DTO dönüşümleri.
 */
@Mapper(
		componentModel = "spring",
		uses = {SetlistSetMapper.class}
)
public interface SetlistMapper {
	
	@Mapping(target = "musicianProfile", ignore = true)
	@Mapping(target = "band", ignore = true)
	@Mapping(target = "sets", ignore = true)
	Setlist toEntity(SetlistCreateRequestDto dto);
	
	SetlistResponseDto toResponse(Setlist entity);
	
	List<SetlistResponseDto> toResponseList(List<Setlist> entities);
}