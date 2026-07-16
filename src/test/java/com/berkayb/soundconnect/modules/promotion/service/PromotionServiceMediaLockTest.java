package com.berkayb.soundconnect.modules.promotion.service;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.promotion.dto.request.PromotionSaveRequestDto;
import com.berkayb.soundconnect.modules.promotion.dto.response.PromotionResponseDto;
import com.berkayb.soundconnect.modules.promotion.entity.Promotion;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionPlacement;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionStatus;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionType;
import com.berkayb.soundconnect.modules.promotion.mapper.PromotionMapper;
import com.berkayb.soundconnect.modules.promotion.repository.PromotionRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionServiceMediaLockTest {

	@Mock PromotionRepository promotionRepository;
	@Mock PromotionMapper promotionMapper;
	@Mock MediaAssetRepository mediaAssetRepository;
	@InjectMocks PromotionServiceImpl service;

	@Test
	void saveLocksReadyPublicImageUntilPromotionReferenceCommits() {
		UUID assetId = UUID.randomUUID();
		PromotionSaveRequestDto request = request(assetId);
		MediaAsset asset = image(MediaStatus.READY, MediaVisibility.PUBLIC);
		Promotion promotion = Promotion.builder().build();
		PromotionResponseDto expected = new PromotionResponseDto(
				null, PromotionType.CAMPAIGN, PromotionPlacement.VENUE_MANAGEMENT_PANEL,
				PromotionStatus.ACTIVE, "Campaign", null, assetId,
				"https://cdn.test/campaign.jpg", null, 0,
				null, null, null, null);

		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
		when(promotionMapper.toEntity(request)).thenReturn(promotion);
		when(promotionRepository.save(promotion)).thenReturn(promotion);
		when(promotionMapper.toResponseDto(promotion)).thenReturn(expected);

		assertThat(service.save(request)).isSameAs(expected);
		assertThat(promotion.getMediaAsset()).isSameAs(asset);
		verify(mediaAssetRepository).findByIdForUpdate(assetId);
	}

	@Test
	void saveRejectsDeletionPendingImageAfterTakingLock() {
		UUID assetId = UUID.randomUUID();
		when(mediaAssetRepository.findByIdForUpdate(assetId))
				.thenReturn(Optional.of(image(MediaStatus.DELETION_PENDING, MediaVisibility.PUBLIC)));

		assertThatThrownBy(() -> service.save(request(assetId)))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.MEDIA_ASSET_NOT_READY));

		verify(promotionRepository, never()).save(any());
	}

	private PromotionSaveRequestDto request(UUID assetId) {
		return new PromotionSaveRequestDto(
				PromotionType.CAMPAIGN,
				PromotionPlacement.VENUE_MANAGEMENT_PANEL,
				PromotionStatus.ACTIVE,
				"Campaign",
				null,
				assetId,
				null,
				0,
				null,
				null);
	}

	private MediaAsset image(MediaStatus status, MediaVisibility visibility) {
		return MediaAsset.builder()
				.kind(MediaKind.IMAGE)
				.status(status)
				.visibility(visibility)
				.sourceUrl("https://cdn.test/campaign.jpg")
				.build();
	}
}
