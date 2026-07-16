package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.repository.MediaAssetReferenceRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Rejects physical deletion while first-party content still points at an asset. */
@Component
@RequiredArgsConstructor
public class MediaAssetReferenceGuard {

	private final MediaAssetReferenceRepository referenceRepository;

	public void assertNotReferenced(UUID assetId) {
		if (assetId == null) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
		}
		if (referenceRepository.countPromotionReferences(assetId) > 0
				|| referenceRepository.countTrackReferences(assetId) > 0
				|| referenceRepository.countProfileMediaReferences(assetId) > 0
				|| referenceRepository.countMusicianProfilePictureReferences(assetId) > 0
				|| referenceRepository.countListenerProfilePictureReferences(assetId) > 0
				|| referenceRepository.countProducerProfilePictureReferences(assetId) > 0
				|| referenceRepository.countOrganizerProfilePictureReferences(assetId) > 0
				|| referenceRepository.countStudioProfilePictureReferences(assetId) > 0
				|| referenceRepository.countVenueProfilePictureReferences(assetId) > 0
				|| referenceRepository.countBandProfilePictureReferences(assetId) > 0
				|| referenceRepository.countEventPosterReferences(assetId.toString()) > 0) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_IN_USE);
		}
	}
}
