package com.berkayb.soundconnect.modules.follow.band.service;

import com.berkayb.soundconnect.modules.follow.band.dto.response.BandFollowResponseDto;

import java.util.List;
import java.util.UUID;

public interface BandFollowService {
	
	void followBand(UUID followerUserId, UUID bandId);
	
	void unfollowBand(UUID followerUserId, UUID bandId);
	
	boolean isFollowingBand(UUID followerUserId, UUID bandId);
	
	long countFollowers(UUID bandId);
	
	List<BandFollowResponseDto> getBandFollowers(UUID bandId);
	
	List<BandFollowResponseDto> getMyFollowedBands(UUID followerUserId);
}