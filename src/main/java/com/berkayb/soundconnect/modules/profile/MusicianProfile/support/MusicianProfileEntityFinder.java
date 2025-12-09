package com.berkayb.soundconnect.modules.profile.MusicianProfile.support;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MusicianProfileEntityFinder {
	private final MusicianProfileRepository musicianProfileRepository;
	
	public MusicianProfile getMusician
}