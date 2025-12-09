package com.berkayb.soundconnect.modules.setlistcreator.mapper;

import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistSetRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.response.SetlistSetResponseDto;
import com.berkayb.soundconnect.modules.setlistcreator.entity.SetlistSet;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * SetlistSet <-> DTO dönüşümleri.
 */
@Mapper(
		componentModel = "spring",
		uses = {SetlistItemMapper.class}
)
public interface SetlistSetMapper {
	
	@Mapping(target = "setlist", ignore = true)
	@Mapping(target = "items", ignore = true)
	SetlistSet toEntity(SetlistSetRequestDto dto);
	
	SetlistSetResponseDto toResponse(SetlistSet entity);
	
	List<SetlistSetResponseDto> toResponseList(List<SetlistSet> entities);
}