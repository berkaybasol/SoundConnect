package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support;


import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class BandEntityFinder {
	private final BandRepository bandRepository;
	private final BandMemberRepository bandMemberRepository;
	
	public Band getBand(UUID id) {
		return bandRepository.findById(id)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_NOT_FOUND));
	}
	
	public BandMember getBandMember(UUID bandId, UUID userId) {
		return bandMemberRepository.findByBandIdAndUserId(bandId, userId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
	}
}