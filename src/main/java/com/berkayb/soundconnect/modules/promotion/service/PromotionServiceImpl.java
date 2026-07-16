package com.berkayb.soundconnect.modules.promotion.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.promotion.dto.request.PromotionSaveRequestDto;
import com.berkayb.soundconnect.modules.promotion.dto.request.PromotionUpdateRequestDto;
import com.berkayb.soundconnect.modules.promotion.dto.response.PromotionResponseDto;
import com.berkayb.soundconnect.modules.promotion.entity.Promotion;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionPlacement;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionStatus;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionType;
import com.berkayb.soundconnect.modules.promotion.mapper.PromotionMapper;
import com.berkayb.soundconnect.modules.promotion.repository.PromotionRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PromotionServiceImpl implements PromotionService{
	
	private final PromotionRepository promotionRepository;
	private final PromotionMapper promotionMapper;
	private final MediaAssetRepository mediaAssetRepository;
	
	@Override
	public PromotionResponseDto save(PromotionSaveRequestDto dto) {
		log.info("promotion kaydi olusturuluyor. title={}, placement={}, type={}", dto.title(), dto.placement(), dto.type());
		
		validateDateRange(dto.startDate(), dto.endDate());
		validatePriority(dto.priority());
		
		MediaAsset mediaAsset = findMediaAssetById(dto.mediaAssetId());
		
		Promotion promotion = promotionMapper.toEntity(dto);
		promotion.setMediaAsset(mediaAsset);
		
		Promotion savedPromotion = promotionRepository.save(promotion);
		
		log.info("promotion kaydi basariyla olusturuldu. promotionId={}", savedPromotion.getId());
		return promotionMapper.toResponseDto(savedPromotion);
	}
	
	@Override
	public PromotionResponseDto update(UUID id, PromotionUpdateRequestDto dto) {
		log.info("promotion kaydi guncelleniyor. promotionId={}, placement={}, type={}",
		         id, dto.placement(), dto.type());
		
		validateDateRange(dto.startDate(), dto.endDate());
		validatePriority(dto.priority());
		
		Promotion promotion = findPromotionById(id);
		MediaAsset mediaAsset = findMediaAssetById(dto.mediaAssetId());
		
		promotionMapper.updateEntityFromDto(dto, promotion);
		promotion.setMediaAsset(mediaAsset);
		
		Promotion updatedPromotion = promotionRepository.save(promotion);
		
		log.info("promotion kaydi basariyla guncellendi. promotionId={}", updatedPromotion.getId());
		return promotionMapper.toResponseDto(updatedPromotion);
	}
	
	@Override
	@Transactional(readOnly = true)
	public PromotionResponseDto getById(UUID id) {
		log.info("promotion kaydi id ile getiriliyor. promotionId={}", id);
		
		Promotion promotion = findPromotionById(id);
		return promotionMapper.toResponseDto(promotion);
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<PromotionResponseDto> getDisplayableByPlacement(PromotionPlacement placement) {
		log.info("yayina uygun promotionlar getiriliyor. placement={}", placement);
		
		LocalDateTime now = LocalDateTime.now();
		return promotionRepository.findAllDisplayableByPlacement(placement, PromotionStatus.ACTIVE, now)
				.stream()
				.map(promotionMapper::toResponseDto)
				.toList();
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<PromotionResponseDto> getAllByPlacement(PromotionPlacement placement) {
		log.info("placement alanina gore tum promotionlar getiriliyor. placement={}", placement);
		
		return promotionRepository.findAllByPlacementOrderByPriorityDescCreatedAtDesc(placement)
				.stream()
				.map(promotionMapper::toResponseDto)
				.toList();
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<PromotionResponseDto> getAllByStatus(PromotionStatus status) {
		log.info("status alanina gore tum promotionlar getiriliyor. status={}", status);
		
		return promotionRepository.findAllByStatusOrderByCreatedAtDesc(status)
				.stream()
				.map(promotionMapper::toResponseDto)
				.toList();
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<PromotionResponseDto> getAllByType(PromotionType type) {
		log.info("type alanina gore promotionlar getiriliyor. type={}", type);
		
		return promotionRepository.findAllByTypeOrderByCreatedAtDesc(type)
		                          .stream()
		                          .map(promotionMapper::toResponseDto)
		                          .toList();
	}
	
	@Override
	public void deleteById(UUID id) {
		log.info("promotion kaydi siliniyor. promotionId={}", id);
		
		Promotion promotion = findPromotionById(id);
		promotionRepository.delete(promotion);
		
		log.info("promotion kaydi basariyla silindi. promotionId={}", id);
	}
	
	
	//helpers
	
	//promotion kaydini id ile bulur
	private Promotion findPromotionById(UUID id) {
		return promotionRepository.findById(id)
				.orElseThrow(() -> {
					log.error("promotion kaydi bulunamadi. promotionId={}", id);
					return new SoundConnectException(ErrorType.PROMOTION_NOT_FOUND);
				});
	}
	
	// promotion icin kullanilacak media kaydini id ile bulur
	private MediaAsset findMediaAssetById(UUID mediaAssetId) {
		MediaAsset asset = mediaAssetRepository.findByIdForUpdate(mediaAssetId)
				.orElseThrow(() -> {
					log.error("media kaydi bulunamadi. mediaId={}", mediaAssetId);
					return new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
				});
		if (asset.getKind() != MediaKind.IMAGE) {
			throw new SoundConnectException(ErrorType.MEDIA_KIND_INVALID);
		}
		if (asset.getStatus() != MediaStatus.READY) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
		}
		if (asset.getVisibility() != MediaVisibility.PUBLIC) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_PUBLIC);
		}
		if (!StringUtils.hasText(asset.getPlaybackUrl()) && !StringUtils.hasText(asset.getSourceUrl())) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_STATE_INVALID);
		}
		return asset;
	}
	
	// promotion tarih araligini dogrular
	private void validateDateRange(LocalDateTime startDate, LocalDateTime endDate) {
		if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
			log.error("gecersiz promotion tarih araligi. startDate={}, endDate={}", startDate, endDate);
			throw new SoundConnectException(ErrorType.PROMOTION_INVALID_DATE_RANGE);
		}
	}
	
	private void validatePriority(Integer priority) {
		if (priority == null || priority < 0) {
			log.error("gecersiz promotion priority degeri. priority={}", priority);
			throw new SoundConnectException(ErrorType.PROMOTION_INVALID_PRIORITY);
		}
	}
}
