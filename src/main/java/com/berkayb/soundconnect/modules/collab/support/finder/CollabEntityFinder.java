package com.berkayb.soundconnect.modules.collab.support.finder;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CollabEntityFinder {
	private final CollabRepository collabRepository;
	
	public Collab getCollab(UUID id) {
		return collabRepository.findById(id)
		                     .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_NOT_FOUND));
	}
}