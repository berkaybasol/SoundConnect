package com.berkayb.soundconnect.modules.follow.band.service;

import com.berkayb.soundconnect.modules.follow.band.dto.response.BandFollowResponseDto;
import com.berkayb.soundconnect.modules.follow.band.entity.BandFollow;
import com.berkayb.soundconnect.modules.follow.band.mapper.BandFollowMapper;
import com.berkayb.soundconnect.modules.follow.band.repository.BandFollowRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
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
		
		// TODO Notification modülü geldiğinde burada async bildirim tetiklenebilir.
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
}