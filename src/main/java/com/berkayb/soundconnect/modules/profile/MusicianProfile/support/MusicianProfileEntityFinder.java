package com.berkayb.soundconnect.modules.profile.MusicianProfile.support;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MusicianProfileEntityFinder {
	private final MusicianProfileRepository musicianProfileRepository;
	
	public MusicianProfile getMusician(UUID id) {
		return musicianProfileRepository.findById(id)
				.orElseThrow(() -> new SoundConnectException(ErrorType.MUSICIAN_NOT_FOUND));
	}
}