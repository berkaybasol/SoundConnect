package com.berkayb.soundconnect.modules.promotion.mapper;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.promotion.dto.request.PromotionSaveRequestDto;
import com.berkayb.soundconnect.modules.promotion.dto.request.PromotionUpdateRequestDto;
import com.berkayb.soundconnect.modules.promotion.dto.response.PromotionResponseDto;
import com.berkayb.soundconnect.modules.promotion.entity.Promotion;
import org.mapstruct.*;

/**
 * Promotion entity ile DTO'lar arasındaki dönüşümleri yönetir.
 *
 * Not:
 * - mediaAsset nesnesini doğrudan request DTO'dan maplemiyoruz.
 * - Çünkü mediaAsset entity'si service katmanında finder/repository üzerinden bulunmalı.
 * - Bu yüzden request -> entity dönüşümünde mediaAsset alanını ignore ediyoruz.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface PromotionMapper {
	
	/**
	 * Yeni promotion oluştururken request verisini entity'ye çevirir.
	 *
	 * Not:
	 * - id, createdAt, updatedAt gibi alanlar BaseEntity tarafından yönetilir.
	 * - mediaAsset service katmanında set edileceği için burada ignore edilir.
	 */
	@Mapping(target = "mediaAsset", ignore = true)
	Promotion toEntity(PromotionSaveRequestDto dto);
	
	/**
	 * Promotion entity'sini response DTO'ya çevirir.
	 */
	@Mapping(target = "mediaAssetId", source = "mediaAsset.id")
	@Mapping(target = "mediaUrl", source = "mediaAsset", qualifiedByName = "mapMediaUrl")
	PromotionResponseDto toResponseDto(Promotion promotion);
	
	/**
	 * Update request verisini mevcut entity üzerine işler.
	 *
	 * Not:
	 * - id alanını entity üzerinde değiştirmiyoruz.
	 * - mediaAsset service katmanında ayrıca bulunup set edileceği için burada ignore edilir.
	 */
	@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
	@Mapping(target = "id", ignore = true)
	@Mapping(target = "mediaAsset", ignore = true)
	@Mapping(target = "createdAt", ignore = true)
	@Mapping(target = "updatedAt", ignore = true)
	void updateEntityFromDto(PromotionUpdateRequestDto dto, @MappingTarget Promotion promotion);
	
	/**
	 * MediaAsset içinden client'ın göstereceği url bilgisini çıkarır.
	 *
	 * Not:
	 * - Öncelik playbackUrl -> sourceUrl şeklinde verildi.
	 * - İleride business kararı değişirse bu method güncellenebilir.
	 */
	@Named("mapMediaUrl")
	default String mapMediaUrl(MediaAsset mediaAsset) {
		if (mediaAsset == null) {
			return null;
		}
		if (mediaAsset.getPlaybackUrl() != null && !mediaAsset.getPlaybackUrl().isBlank()) {
			return mediaAsset.getPlaybackUrl();
		}
		return mediaAsset.getSourceUrl();
	}
}