// modules/media/mapper/MediaAssetMapper.java
package com.berkayb.soundconnect.modules.media.mapper;

import com.berkayb.soundconnect.modules.media.dto.response.MediaResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.data.domain.Page;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MediaAssetMapper {
	
	// entity -> dto
	@Mapping(target = "uuid", source = "id")
	MediaResponseDto toDto(MediaAsset entity);
	
	// list helper
	List<MediaResponseDto> toDtoList(List<MediaAsset> entities);
	
	// page helper (MapStruct yerine Page.map ile)
	default Page<MediaResponseDto> toDtoPage(Page<MediaAsset> page) {
		return page.map(this::toDto);
	}
}