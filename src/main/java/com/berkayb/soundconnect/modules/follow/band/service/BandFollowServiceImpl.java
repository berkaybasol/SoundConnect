package com.berkayb.soundconnect.modules.follow.band.service;

import com.berkayb.soundconnect.modules.follow.band.dto.response.BandFollowResponseDto;
import com.berkayb.soundconnect.modules.follow.band.entity.BandFollow;
import com.berkayb.soundconnect.modules.follow.band.mapper.BandFollowMapper;
import com.berkayb.soundconnect.modules.follow.band.repository.BandFollowRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.profile.shared.resolver.dto.UserProfileTargetDto;
import com.berkayb.soundconnect.modules.profile.shared.resolver.service.PublicProfileResolverService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationInboundEvent;
import com.berkayb.soundconnect.shared.messaging.events.notification.NotificationProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BandFollowServiceImpl implements BandFollowService {
	
	private final BandFollowRepository bandFollowRepository;
	private final UserEntityFinder userEntityFinder;
	private final BandEntityFinder bandEntityFinder;
	private final BandFollowMapper bandFollowMapper;
	private final MediaAssetService mediaAssetService;
	private final NotificationProducer notificationProducer;
	private final PublicProfileResolverService publicProfileResolverService;
	
	@Override
	@Transactional
	public void followBand(UUID followerUserId, UUID bandId) {
		User follower = userEntityFinder.getUser(followerUserId);
		Band band = bandEntityFinder.getBand(bandId);
		
		validateBandMembershipForFollow(bandId, followerUserId);
		
		if (bandFollowRepository.existsByFollowerAndBand(follower, band)) {
			log.warn("User already follows band. userId={}, bandId={}", followerUserId, bandId);
			throw new SoundConnectException(ErrorType.BAND_ALREADY_FOLLOWED);
		}
		
		BandFollow bandFollow = BandFollow.builder()
		                                  .follower(follower)
		                                  .band(band)
		                                  .followedAt(LocalDateTime.now())
		                                  .build();
		
		bandFollowRepository.save(bandFollow);
		
		log.info("Band followed successfully. userId={}, bandId={}", followerUserId, bandId);
		
		publishBandFollowerNotification(follower, band);
	}
	
	@Override
	@Transactional
	public void unfollowBand(UUID followerUserId, UUID bandId) {
		User follower = userEntityFinder.getUser(followerUserId);
		Band band = bandEntityFinder.getBand(bandId);
		
		BandFollow bandFollow = bandFollowRepository.findByFollowerAndBand(follower, band)
		                                            .orElseThrow(() -> {
			                                            log.warn("Band follow relation not found. userId={}, bandId={}", followerUserId, bandId);
			                                            return new SoundConnectException(ErrorType.BAND_FOLLOW_RELATION_NOT_FOUND);
		                                            });
		
		bandFollowRepository.delete(bandFollow);
		
		log.info("Band unfollowed successfully. userId={}, bandId={}", followerUserId, bandId);
	}
	
	@Override
	@Transactional(readOnly = true)
	public boolean isFollowingBand(UUID followerUserId, UUID bandId) {
		User follower = userEntityFinder.getUser(followerUserId);
		Band band = bandEntityFinder.getBand(bandId);
		
		return bandFollowRepository.existsByFollowerAndBand(follower, band);
	}
	
	@Override
	@Transactional(readOnly = true)
	public long countFollowers(UUID bandId) {
		Band band = bandEntityFinder.getBand(bandId);
		return bandFollowRepository.countByBand(band);
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<BandFollowResponseDto> getBandFollowers(UUID bandId) {
		Band band = bandEntityFinder.getBand(bandId);
		
		return bandFollowRepository.findAllByBand(band)
		                           .stream()
		                           .map(this::toResponseDto)
		                           .toList();
	}
	
	@Override
	@Transactional(readOnly = true)
	public List<BandFollowResponseDto> getMyFollowedBands(UUID followerUserId) {
		User follower = userEntityFinder.getUser(followerUserId);
		
		return bandFollowRepository.findAllByFollower(follower)
		                           .stream()
		                           .map(this::toResponseDto)
		                           .toList();
	}
	
	private void validateBandMembershipForFollow(UUID bandId, UUID followerUserId) {
		BandMember member = null;
		try {
			member = bandEntityFinder.getBandMember(bandId, followerUserId);
		} catch (SoundConnectException ex) {
			return;
		}
		
		if (member.getStatus() == BandMemberShipStatus.ACTIVE) {
			log.warn("Active band member cannot follow their own band. userId={}, bandId={}", followerUserId, bandId);
			throw new SoundConnectException(ErrorType.BAND_MEMBER_CANNOT_FOLLOW_OWN_BAND);
		}
	}
	
	private BandFollowResponseDto toResponseDto(BandFollow bandFollow) {
		var baseDto = bandFollowMapper.toDto(bandFollow);
		String bandProfilePictureUrl = resolveBandProfilePictureUrl(bandFollow.getBand().getProfilePictureMediaId());
		
		return new BandFollowResponseDto(
				baseDto.followId(),
				baseDto.followerId(),
				baseDto.followerUsername(),
				baseDto.followerProfilePicture(),
				baseDto.bandId(),
				baseDto.bandName(),
				baseDto.bandProfilePictureMediaId(),
				bandProfilePictureUrl,
				baseDto.followedAt()
		);
	}
	
	private String resolveBandProfilePictureUrl(UUID mediaAssetId) {
		if (mediaAssetId == null) {
			return null;
		}
		
		try {
			return mediaAssetService.getById(mediaAssetId).getSourceUrl();
		} catch (Exception e) {
			log.warn("Band profile picture resolve failed. mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
	
	private void publishBandFollowerNotification(User follower, Band band) {
		try {
			for (BandMember member : band.getMembers()) {
				if (member.getStatus() != BandMemberShipStatus.ACTIVE || member.getUser() == null) {
					continue;
				}
				UUID recipientId = member.getUser().getId();
				if (recipientId == null || recipientId.equals(follower.getId())) {
					continue;
				}
				notificationProducer.publish(
						NotificationInboundEvent.builder()
						                        .recipientId(recipientId)
						                        .type(NotificationType.SOCIAL_NEW_BAND_FOLLOWER)
						                        .title(safe(follower.getUsername(), "Bir kullanıcı") + " bandını takip etmeye başladı")
						                        .message(safe(band.getName(), "Band") + " yeni bir takipçi kazandı.")
						                        .payload(bandFollowerPayload(follower, band))
						                        .emailForce(false)
						                        .occurredAt(Instant.now())
						                        .build()
				);
			}
		} catch (Exception e) {
			log.warn("Band follow notification publish failed. follower={}, band={}, err={}",
			         follower.getId(), band.getId(), e.toString());
		}
	}
	
	private Map<String, Object> bandFollowerPayload(User follower, Band band) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("module", "SOCIAL");
		payload.put("action", "NEW_BAND_FOLLOWER");
		payload.put("followerId", follower.getId().toString());
		payload.put("followerUsername", safe(follower.getUsername(), "Bir kullanıcı"));
		payload.put("bandId", band.getId().toString());
		payload.put("bandName", safe(band.getName(), "Band"));
		putIfPresent(payload, "followerAvatarUrl", resolveFollowerProfilePictureUrl(follower));
		return payload;
	}

	private String resolveFollowerProfilePictureUrl(User follower) {
		return publicProfileResolverService.resolveByUserId(follower.getId()).profiles().stream()
				.map(UserProfileTargetDto::profilePictureUrl)
				.filter(this::notBlank)
				.findFirst()
				.orElse(follower.getProfilePicture());
	}

	private void putIfPresent(Map<String, Object> payload, String key, String value) {
		if (value != null && !value.isBlank()) payload.put(key, value.trim());
	}

	private String safe(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private boolean notBlank(String value) {
		return value != null && !value.isBlank();
	}
}
