package com.berkayb.soundconnect.modules.engagement.service;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class EngagementTargetValidatorImpl implements EngagementTargetValidator{
	
	private final OverthinkingPostRepository overthinkingPostRepository;
	private final MediaAssetRepository mediaAssetRepository;
	private final EventRepository eventRepository;
	
	//FIXME targetla alakali seyler gelirse buraya eklencek
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void validateExists(EngagementTargetType targetType, UUID targedId) {
		boolean exists = switch (targetType) {
			case OVERTHINKING -> overthinkingPostRepository.existsById(targedId);
			case MEDIA -> lockPublicReadyMedia(targedId);
			case EVENT -> eventRepository.existsById(targedId);
		};
		
		if (!exists) {
			log.warn("[EngagementTargetValidator] Target not found. type={}, targetId={}", targetType,targedId);
			throw new SoundConnectException(ErrorType.ENGAGEMENT_NOT_FOUND);
		}
		log.debug("[EngagementTargetValidator] Target validated. type={}, targetId={}", targetType);
	}

	private boolean lockPublicReadyMedia(UUID mediaAssetId) {
		return mediaAssetRepository.findByIdForUpdate(mediaAssetId)
				.filter(asset -> asset.getStatus() == MediaStatus.READY)
				.filter(asset -> asset.getVisibility() == MediaVisibility.PUBLIC)
				.filter(this::hasRenderableMediaUrl)
				.isPresent();
	}

	private boolean hasRenderableMediaUrl(MediaAsset asset) {
		return StringUtils.hasText(asset.getPlaybackUrl()) || StringUtils.hasText(asset.getSourceUrl());
	}
}
