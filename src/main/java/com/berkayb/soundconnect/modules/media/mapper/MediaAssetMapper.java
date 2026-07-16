// modules/media/mapper/MediaAssetMapper.java
package com.berkayb.soundconnect.modules.media.mapper;

import com.berkayb.soundconnect.modules.media.dto.response.MediaResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.data.domain.Page;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MediaAssetMapper {
	
	// entity -> dto
	@Mapping(target = "uuid", source = "id")
	@Mapping(target = "sourceUrl", expression = "java(exposedUrl(entity, entity.getSourceUrl()))")
	@Mapping(target = "playbackUrl", expression = "java(exposedUrl(entity, entity.getPlaybackUrl()))")
	@Mapping(target = "thumbnailUrl", expression = "java(exposedUrl(entity, entity.getThumbnailUrl()))")
	MediaResponseDto toDto(MediaAsset entity);

	/**
	 * URL fields cross the API boundary only for fully published assets. In
	 * particular, owner list responses must not expose legacy persisted URLs for
	 * PRIVATE/UNLISTED objects.
	 */
	default String exposedUrl(MediaAsset entity, String url) {
		return entity != null
				&& entity.getStatus() == MediaStatus.READY
				&& entity.getVisibility() == MediaVisibility.PUBLIC
				? url
				: null;
	}
	
	// list helper
	List<MediaResponseDto> toDtoList(List<MediaAsset> entities);
	
	// page helper (MapStruct yerine Page.map ile)
	default Page<MediaResponseDto> toDtoPage(Page<MediaAsset> page) {
		return page.map(this::toDto);
	}
}
