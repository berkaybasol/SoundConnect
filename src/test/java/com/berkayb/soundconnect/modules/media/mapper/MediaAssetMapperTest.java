// src/test/java/com/berkayb/soundconnect/modules/media/mapper/MediaAssetMapperTest.java
package com.berkayb.soundconnect.modules.media.mapper;

import com.berkayb.soundconnect.modules.media.dto.response.MediaResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("mapper")
class MediaAssetMapperTest {
	
	private final MediaAssetMapper mapper = Mappers.getMapper(MediaAssetMapper.class);
	
	private static MediaAsset entity(MediaKind kind) {
		return MediaAsset.builder()
		                 .kind(kind)
		                 .status(MediaStatus.READY)
		                 .visibility(MediaVisibility.PUBLIC)
		                 .ownerType(MediaOwnerType.USER)
		                 .ownerId(UUID.randomUUID())
		                 .storageKey("media/" + UUID.randomUUID() + "/source.mp4")
		                 .sourceUrl("https://cdn/source.mp4")
		                 .playbackUrl(kind == MediaKind.VIDEO ? "https://cdn/master.m3u8" : "https://cdn/source.mp4")
		                 .thumbnailUrl("https://cdn/thumb.jpg")
		                 .mimeType(kind == MediaKind.IMAGE ? "image/png" : kind == MediaKind.AUDIO ? "audio/mpeg" : "video/mp4")
		                 .size(42_000L)
		                 .durationSeconds(kind == MediaKind.VIDEO ? 123 : null)
		                 .width(kind == MediaKind.VIDEO ? 1280 : null)
		                 .height(kind == MediaKind.VIDEO ? 720 : null)
		                 .title("title")
		                 .description("desc")
		                 .streamingProtocol(kind == MediaKind.VIDEO ? MediaStreamingProtocol.HLS : MediaStreamingProtocol.PROGRESSIVE)
		                 .build();
	}
	
	@Test
	void toDto_maps_all_relevant_fields_and_uuid_from_id() {
		MediaAsset e = entity(MediaKind.VIDEO);
		UUID forcedId = UUID.randomUUID();
		e.setId(forcedId); // uuid <- id bekliyoruz
		
		MediaResponseDto dto = mapper.toDto(e);
		
		// id -> uuid
		assertThat(dto.uuid()).isEqualTo(forcedId);
		
		// birebir alanlar
		assertThat(dto.kind()).isEqualTo(e.getKind());
		assertThat(dto.status()).isEqualTo(e.getStatus());
		assertThat(dto.visibility()).isEqualTo(e.getVisibility());
		assertThat(dto.ownerType()).isEqualTo(e.getOwnerType());
		assertThat(dto.ownerId()).isEqualTo(e.getOwnerId());
		assertThat(dto.sourceUrl()).isEqualTo(e.getSourceUrl());
		assertThat(dto.playbackUrl()).isEqualTo(e.getPlaybackUrl());
		assertThat(dto.thumbnailUrl()).isEqualTo(e.getThumbnailUrl());
		assertThat(dto.mimeType()).isEqualTo(e.getMimeType());
		assertThat(dto.size()).isEqualTo(e.getSize());
		assertThat(dto.durationSeconds()).isEqualTo(e.getDurationSeconds());
		assertThat(dto.width()).isEqualTo(e.getWidth());
		assertThat(dto.height()).isEqualTo(e.getHeight());
		assertThat(dto.title()).isEqualTo(e.getTitle());
		assertThat(dto.description()).isEqualTo(e.getDescription());
		assertThat(dto.streamingProtocol()).isEqualTo(e.getStreamingProtocol());
	}
	
	@Test
	void toDto_handles_nonVideo_nullables() {
		MediaAsset img = entity(MediaKind.IMAGE);
		img.setId(UUID.randomUUID());
		
		MediaResponseDto dto = mapper.toDto(img);
		
		// video-meta alanları image için null olabilir
		assertThat(dto.durationSeconds()).isNull();
		assertThat(dto.width()).isNull();
		assertThat(dto.height()).isNull();
		
		// progressive bekliyoruz
		assertThat(dto.streamingProtocol()).isEqualTo(MediaStreamingProtocol.PROGRESSIVE);
	}
	
	@Test
	void toDtoList_maps_all_items_preserving_order() {
		MediaAsset a = entity(MediaKind.IMAGE); a.setId(UUID.randomUUID());
		MediaAsset b = entity(MediaKind.AUDIO); b.setId(UUID.randomUUID());
		
		List<MediaResponseDto> list = mapper.toDtoList(List.of(a, b));
		
		assertThat(list).hasSize(2);
		assertThat(list.get(0).uuid()).isEqualTo(a.getId());
		assertThat(list.get(1).uuid()).isEqualTo(b.getId());
	}
	
	@Test
	void toDtoPage_maps_page_content_and_metadata() {
		MediaAsset a = entity(MediaKind.VIDEO); a.setId(UUID.randomUUID());
		MediaAsset b = entity(MediaKind.VIDEO); b.setId(UUID.randomUUID());
		
		Page<MediaAsset> page = new PageImpl<>(List.of(a, b));
		Page<MediaResponseDto> mapped = mapper.toDtoPage(page);
		
		assertThat(mapped.getTotalElements()).isEqualTo(2);
		assertThat(mapped.getContent()).extracting(MediaResponseDto::uuid)
		                               .containsExactly(a.getId(), b.getId());
	}
}