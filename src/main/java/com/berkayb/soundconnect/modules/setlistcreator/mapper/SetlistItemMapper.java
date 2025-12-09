package com.berkayb.soundconnect.modules.setlistcreator.mapper;

import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistItemRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.response.SetlistItemResponseDto;
import com.berkayb.soundconnect.modules.setlistcreator.entity.SetlistItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * SetlistItem <-> DTO dönüşümlerini yapar.
 * Parent ilişkisi (SetlistSet) service katmanında set edilir.
 */
@Mapper(componentModel = "spring")
public interface SetlistItemMapper {
	
	@Mapping(target = "set", ignore = true)
	SetlistItem toEntity(SetlistItemRequestDto dto);
	
	SetlistItemResponseDto toResponse(SetlistItem entity);
}