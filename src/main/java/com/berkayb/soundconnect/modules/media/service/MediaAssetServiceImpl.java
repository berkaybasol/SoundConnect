package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.modules.media.abuse.MediaUploadAbuseGuard;
import com.berkayb.soundconnect.modules.engagement.service.MediaEngagementCleanupService;
import com.berkayb.soundconnect.modules.media.dto.response.MediaAccessUrlResponseDto;
import com.berkayb.soundconnect.modules.media.dto.response.UploadInitResultResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.image.ImageThumbnailRequestedEvent;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionRequestedEvent;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionProperties;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.MediaMimeType;
import com.berkayb.soundconnect.modules.media.storage.MediaContentSignatureValidator;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectMetadata;
import com.berkayb.soundconnect.modules.media.storage.StorageAccessUrl;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow;
import com.berkayb.soundconnect.modules.media.transcode.MediaTranscodeQueuedEvent;
import com.berkayb.soundconnect.modules.media.verification.MediaUploadVerificationCoordinator;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository.OrganizerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.repository.ProducerProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;


//------------------------------TAKILDIGIN NOKTADA MediaModule.md DOSYASINA BAK!----------------------------------------


import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MediaAssetServiceImpl implements MediaAssetService {
	
	
	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> getPlaybackUrlMap(List<UUID> mediaAssetIds) {
		if (mediaAssetIds == null || mediaAssetIds.isEmpty()) return Map.of();
		return mediaAssetRepository.findAllById(mediaAssetIds).stream()
		                           .filter(this::isPubliclyPlayable)
		                           .collect(Collectors.toMap(MediaAsset::getId, MediaAsset::getPlaybackUrl));
	}
	
	@Override
	public MediaAsset getById(UUID mediaAssetId) {
		return mediaAssetRepository.findById(mediaAssetId)
		                                       .orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
	}
	
	@Override
	@Transactional(readOnly = true)
	public String getPlaybackUrl(UUID mediaAssetId) {
		MediaAsset asset = mediaAssetRepository.findById(mediaAssetId)
		                                       .orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		if (!isPubliclyPlayable(asset)) {
			// Do not disclose whether a private or incomplete object exists.
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
		}
		return asset.getPlaybackUrl();
	}

	@Override
	@Transactional(readOnly = true)
	public String getDisplayUrl(UUID mediaAssetId) {
		MediaAsset asset = mediaAssetRepository.findById(mediaAssetId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		if (asset.getStatus() != MediaStatus.READY || asset.getVisibility() != MediaVisibility.PUBLIC) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
		}
		if ((asset.getKind() == MediaKind.IMAGE || asset.getKind() == MediaKind.VIDEO)
				&& StringUtils.hasText(asset.getThumbnailUrl())) {
			return asset.getThumbnailUrl();
		}
		if (!StringUtils.hasText(asset.getPlaybackUrl())) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
		}
		return asset.getPlaybackUrl();
	}

	@Override
	@Transactional(readOnly = true)
	public MediaAsset getPublicReadyById(UUID mediaAssetId) {
		MediaAsset asset = mediaAssetRepository.findById(mediaAssetId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		if (!isPubliclyPlayable(asset)) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
		}
		return asset;
	}

	@Override
	@Transactional(readOnly = true)
	public MediaAccessUrlResponseDto createOwnerAccessUrl(UUID actingUserId, UUID mediaAssetId) {
		MediaAsset asset = mediaAssetRepository.findById(mediaAssetId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		if (!canActForOwner(actingUserId, asset.getOwnerType(), asset.getOwnerId())) {
			// Keep protected object identifiers non-enumerable across principals.
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
		}
		if (asset.getStatus() != MediaStatus.READY) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_READY);
		}
		if (asset.getVisibility() == MediaVisibility.PUBLIC
				|| asset.getKind() == MediaKind.VIDEO
				|| !StorageObjectKeys.isProtected(asset.getStorageKey())) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_STATE_INVALID);
		}
		StorageAccessUrl accessUrl = storageClient.createPresignedGetUrl(asset.getStorageKey());
		return new MediaAccessUrlResponseDto(asset.getId(), accessUrl.url(), accessUrl.expiresAt());
	}

	@Override
	@Transactional
	public void validateAssignableMedia(
			UUID actingUserId,
			UUID mediaAssetId,
			MediaOwnerType ownerType,
			UUID ownerId,
			MediaKind expectedKind
	) {
		assertCanActForOwner(actingUserId, ownerType, ownerId);
		// Reference writers and delete() serialize on the same row lock. When
		// invoked from a transactional profile service, this lock is retained until
		// the referencing row/UUID field commits.
		MediaAsset asset = mediaAssetRepository.findByIdForUpdate(mediaAssetId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		if (asset.getOwnerType() != ownerType
				|| !ownerId.equals(asset.getOwnerId())
				|| asset.getKind() != expectedKind
				|| !isPubliclyPlayable(asset)) {
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND);
		}
	}

	private boolean isPubliclyPlayable(MediaAsset asset) {
		return asset.getStatus() == MediaStatus.READY
				&& asset.getVisibility() == MediaVisibility.PUBLIC
				&& StringUtils.hasText(asset.getPlaybackUrl());
	}
	
	private final MediaAssetRepository mediaAssetRepository;
	private final BandRepository bandRepository;
	private final VenueRepository venueRepository;
	private final MusicianProfileRepository musicianProfileRepository;
	private final ProducerProfileRepository producerProfileRepository;
	private final OrganizerProfileRepository organizerProfileRepository;
	private final StudioProfileRepository studioProfileRepository;
	private final ListenerProfileRepository listenerProfileRepository;
	private final VenueProfileRepository venueProfileRepository;
	
	// dosya depolama (S3/R2) islemleri icin storage client
	private final StorageClient storageClient;
	
	// dosya turu, mimeType, boyut namin kurallarini yoneten policy katmani
	private final MediaPolicy mediaPolicy;
	
	// Published inside the DB transaction and delivered only after commit.
	private final ApplicationEventPublisher applicationEventPublisher;
	private final MediaUploadAbuseGuard mediaUploadAbuseGuard;
	private final MediaUploadVerificationCoordinator mediaUploadVerificationCoordinator;
	private final MediaAssetReferenceGuard mediaAssetReferenceGuard;
	private final MediaEngagementCleanupService mediaEngagementCleanupService;
	private final PresignedUploadWriteWindow presignedUploadWriteWindow;
	private final MediaDeletionProperties mediaDeletionProperties;
	
	/**
	 * Kullanici medya yukleme istegi gonderdiginde bu metod calsiir.
	 * Islem akisi:
	 * Yukleme kurallari check edilir (boyut/mimeType/tur)
	 * DB'ye taslak (draft) bir MediaAsset kaydi olusturulur(status: UPLOADING).
	 * Dosya depolama icin unique bir storage key uretilir (orn: "media/{assetId}-originalname.
	 * Storage servisinden (S3/R2) dosyayi dogrudan yuklemek icin bbir presigned PUT URL alinir.
	 * DB'deki asset kaydi guncellenir (storageKey, sourceUrl atanir.)
	 * Client'a assetId ve yukleme URL'si donulur.
	 */
	@Override
	@Transactional
	public UploadInitResultResponseDto initUpload(UUID actingUserId, MediaOwnerType ownerType, UUID ownerId, MediaKind kind, MediaVisibility visibility, String mimeType, long sizeBytes, String originalFileName) {
		assertCanActForOwner(actingUserId, ownerType, ownerId);
		if (visibility == null) {
			throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_INVALID_REQUEST);
		}
		assertSupportedVisibility(kind, visibility);
		mimeType = MediaMimeType.sanitize(mimeType);
		
		// yukleme politikalarini dogrula
		// (mime, boyut, tur kurallarini kontrol et
		mediaPolicy.validate(kind, mimeType, sizeBytes);
		
		// taslak media asset kaydi olustur
		// ilk olarak sadece temel bilgilerle (status: UPLOADING) bir kayit aciyoruz.
		MediaAsset draft = MediaAsset.builder()
				.kind(kind)
				.status(MediaStatus.UPLOADING)
				.visibility(visibility)
				.ownerType(ownerType)
				.ownerId(ownerId)
				.mimeType(mimeType)
				.size(sizeBytes)
				.streamingProtocol(
						kind == MediaKind.VIDEO
								? MediaStreamingProtocol.HLS // videolar icin HLS kullaniyoruz
								: MediaStreamingProtocol.PROGRESSIVE // gorsel ve auidio icin progressive
				)
				.build();
		
		// DB'ye taslak kaydi ekle
		// assetId otomatik olarak burada uretilir.
		draft = mediaAssetRepository.save(draft);
		mediaUploadAbuseGuard.reserve(actingUserId, draft.getId(), sizeBytes);
		mediaUploadAbuseGuard.releaseAfterRollback(draft.getId());
		try {
			String sourceKey = mediaPolicy.buildSourceKey(draft.getId(), mimeType);
			if (visibility == MediaVisibility.PUBLIC) {
				sourceKey = StorageObjectKeys.quarantineKey(sourceKey);
			} else {
				sourceKey = StorageObjectKeys.protectedKey(sourceKey);
			}

			String uploadUrl = storageClient.createPresignedPutUrl(sourceKey, mimeType, sizeBytes);

			draft.setStorageKey(sourceKey);
			// Captured after signing, making the per-row deadline conservative even
			// if signing itself took measurable time or a later deploy changes TTL.
			draft.setUploadWriteAuthorityExpiresAt(
					presignedUploadWriteWindow.deadlineForNewSignature());
			// Untrusted bytes never receive a stable public URL. PUBLIC image/audio
			// are promoted only after metadata + signature validation; VIDEO source
			// remains private and only its generated HLS tree is public.
			draft.setSourceUrl(null);
			mediaAssetRepository.save(draft);

			log.info("[media] initUpload assetId={} ownerType={} ownerId={} kind={} size={} mime={}",
					draft.getId(), ownerType, ownerId, kind, sizeBytes, mimeType);
			return UploadInitResultResponseDto.builder()
					.assetId(draft.getId())
					.uploadUrl(uploadUrl)
					.build();
		} catch (RuntimeException exception) {
			// No signed URL escaped this transaction, so release the phantom slot.
			mediaUploadAbuseGuard.release(draft.getId());
			throw exception;
		}
	}
	
	
	@Override
	public MediaAsset completeUpload(UUID actingUserId, UUID assetId) {
		// Authorization happens before the durable claim. The coordinator repeats
		// the immutable owner identity check under its short row-lock transaction.
		MediaAsset asset = mediaAssetRepository.findById(assetId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		assertCanActForOwner(actingUserId, asset.getOwnerType(), asset.getOwnerId());
		assertSupportedVisibility(asset.getKind(), asset.getVisibility());
		return mediaUploadVerificationCoordinator.complete(
				assetId, asset.getOwnerType(), asset.getOwnerId());
	}

	// belirli bir owner'a ait tum assetleri her statu ve gorunlurlukte sayfali olarak dondurur.
	@Override
	@Transactional (readOnly = true)
	public Page<MediaAsset> listByOwner(UUID actingUserId, MediaOwnerType ownerType, UUID ownerId, Pageable pageable) {
		assertCanActForOwner(actingUserId, ownerType, ownerId);
		return mediaAssetRepository.findByOwnerTypeAndOwnerId(ownerType, ownerId, pageable);
	}
	
	// belirli bir owner ve belirli bir media turune (auidio, video, image) sahip asset'leri dondurur.
	@Transactional (readOnly = true)
	@Override
	public Page<MediaAsset> listByOwnerAndKind(UUID actingUserId, MediaOwnerType ownerType, UUID ownerId, MediaKind kind, Pageable pageable) {
		assertCanActForOwner(actingUserId, ownerType, ownerId);
		return mediaAssetRepository.findByOwnerTypeAndOwnerIdAndKind(ownerType, ownerId, kind, pageable);
	}
	
	// sadece public ve ready assetleri dondur
	@Transactional (readOnly = true)
	@Override
	public Page<MediaAsset> listPublicByOwner(MediaOwnerType ownerType, UUID ownerId, Pageable pageable) {
		return mediaAssetRepository.findByOwnerTypeAndOwnerIdAndVisibilityAndStatus(
				ownerType, ownerId, MediaVisibility.PUBLIC, MediaStatus.READY, pageable
		);
	}
	
	// belirli bir ownerin belirli bir turdeki public ve ready assetlerini dondurur.
	@Transactional (readOnly = true)
	@Override
	public Page<MediaAsset> listPublicByOwnerAndKind(MediaOwnerType ownerType, UUID ownerId, MediaKind kind, Pageable pageable) {
		return mediaAssetRepository.findByOwnerTypeAndOwnerIdAndKindAndVisibilityAndStatus(
				ownerType, ownerId, kind, MediaVisibility.PUBLIC, MediaStatus.READY, pageable);
	}
	
	
	@Override
	@Transactional
	public void delete(UUID assetId, UUID actingUserId, MediaOwnerType actingAsType, UUID actingAsId) {
		MediaAsset asset = mediaAssetRepository.findByIdForUpdate(assetId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MEDIA_ASSET_NOT_FOUND));
		assertCanActForOwner(actingUserId, actingAsType, actingAsId);

		boolean ownerMatch = asset.getOwnerType() == actingAsType && asset.getOwnerId().equals(actingAsId);

		if (!ownerMatch) {
			log.warn("[media] delete denied assetId={} actingAsType={} actingAsId={}", assetId, actingAsType, actingAsId);
			throw new SoundConnectException(ErrorType.MEDIA_ASSET_DELETE_FORBIDDEN);
		}
		// Do not remove bytes that are still part of first-party content. The check
		// runs while the asset row is locked and before the durable deletion intent is
		// published, so a rejected request has no storage or state side effects.
		mediaAssetReferenceGuard.assertNotReferenced(assetId);
		// Likes and comments belong to the target and are removed atomically. They
		// must never let another user veto the owner's deletion request.
		mediaEngagementCleanupService.purgeForMedia(assetId);
		// The row is the durable deletion intent. Object storage is intentionally
		// untouched until this transaction commits, so a rollback cannot resurrect a
		// database row whose bytes have already been removed.
		LocalDateTime deletionRequestedAt = LocalDateTime.now(ZoneOffset.UTC);
		asset.setStatus(MediaStatus.DELETION_PENDING);
		asset.setDeletionRequestedAt(deletionRequestedAt);
		asset.setPhysicalDeletionNotBefore(
				physicalDeletionNotBefore(asset, deletionRequestedAt));
		mediaAssetRepository.save(asset);
		applicationEventPublisher.publishEvent(new MediaDeletionRequestedEvent(assetId));
		mediaUploadAbuseGuard.releaseAfterCommit(assetId);
		log.info("[media] deletion requested assetId={} by actingAsType={} actingAsId={}",
				assetId, actingAsType, actingAsId);
	}

	private LocalDateTime physicalDeletionNotBefore(
			MediaAsset asset,
			LocalDateTime deletionRequestedAt
	) {
		if (asset.getKind() == MediaKind.VIDEO) {
			return deletionRequestedAt.plus(
					mediaDeletionProperties.getPublicVideoProducerGrace());
		}
		if (asset.getKind() == MediaKind.IMAGE
				&& asset.getVisibility() == MediaVisibility.PUBLIC) {
			return deletionRequestedAt.plus(
					mediaDeletionProperties.getPublicImageProducerGrace());
		}
		return deletionRequestedAt;
	}
	
	@Override
	public boolean exists(UUID mediaAssetId) {
		return mediaAssetRepository.existsById(mediaAssetId);
	}

	private void assertSupportedVisibility(MediaKind kind, MediaVisibility visibility) {
		// Private HLS requires signed manifests and every referenced segment. Until
		// that distribution contract exists, accepting it would create public leaks
		// or broken playback, so it is deliberately fail-closed.
		if (kind == MediaKind.VIDEO && visibility != MediaVisibility.PUBLIC) {
			throw new SoundConnectException(ErrorType.MEDIA_UPLOAD_INVALID_REQUEST);
		}
	}

	private void assertCanActForOwner(UUID actingUserId, MediaOwnerType ownerType, UUID ownerId) {
		if (ownerType == null || ownerId == null || !canActForOwner(actingUserId, ownerType, ownerId)) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private boolean canActForOwner(UUID actingUserId, MediaOwnerType ownerType, UUID ownerId) {
		return switch (ownerType) {
			case USER -> ownerId.equals(actingUserId);
			case BAND -> bandRepository.findById(ownerId)
					.map(band -> canManageBand(actingUserId, band))
					.orElse(false);
			case VENUE -> venueRepository.findById(ownerId)
					.map(venue -> venue.getOwner() != null && venue.getOwner().getId().equals(actingUserId))
					.orElse(false);
			case MUSICIAN_PROFILE -> musicianProfileRepository.findById(ownerId)
					.map(profile -> profile.getUser() != null && profile.getUser().getId().equals(actingUserId))
					.orElse(false);
			case PRODUCER_PROFILE -> producerProfileRepository.findById(ownerId)
					.map(profile -> profile.getUser() != null && profile.getUser().getId().equals(actingUserId))
					.orElse(false);
			case ORGANIZER_PROFILE -> organizerProfileRepository.findById(ownerId)
					.map(profile -> profile.getUser() != null && profile.getUser().getId().equals(actingUserId))
					.orElse(false);
			case STUDIO_PROFILE -> studioProfileRepository.findById(ownerId)
					.map(profile -> profile.getUser() != null && profile.getUser().getId().equals(actingUserId))
					.orElse(false);
			case LISTENER_PROFILE -> listenerProfileRepository.findById(ownerId)
					.map(profile -> profile.getUser() != null && profile.getUser().getId().equals(actingUserId))
					.orElse(false);
			case VENUE_PROFILE -> venueProfileRepository.findById(ownerId)
					.map(profile -> profile.getVenue() != null
							&& profile.getVenue().getOwner() != null
							&& profile.getVenue().getOwner().getId().equals(actingUserId))
					.orElse(false);
			case MUSIC_HOUSE_PROFILE, MANAGER_PROFILE -> false;
		};
	}

	private boolean canManageBand(UUID actingUserId, Band band) {
		return band.getMembers().stream()
				.anyMatch(member -> member.getUser() != null
						&& member.getUser().getId().equals(actingUserId)
						&& member.getStatus() == BandMemberShipStatus.ACTIVE
						&& (member.getBandRole() == BandRole.FOUNDER || member.getBandRole() == BandRole.MANAGER));
	}
	
	
}
