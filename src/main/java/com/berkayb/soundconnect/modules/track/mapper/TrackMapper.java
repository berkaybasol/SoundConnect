package com.berkayb.soundconnect.modules.track.mapper;

import com.berkayb.soundconnect.modules.track.dto.response.TrackResponseDto;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * Track entity → TrackResponseDto dönüşümü.
 * playbackUrl hesaplaması MediaAssetService üzerinden yapılır.
 */
@Mapper(componentModel = "spring")
public interface TrackMapper {
	
	@Mapping(target = "playbackUrl",
			expression = "java(mediaAssetService.getPlaybackUrl(track.getMediaAssetId()))")
	TrackResponseDto toDto(Track track, @Context MediaAssetService mediaAssetService);
}