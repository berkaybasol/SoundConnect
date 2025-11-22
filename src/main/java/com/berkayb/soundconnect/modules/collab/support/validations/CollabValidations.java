package com.berkayb.soundconnect.modules.collab.support.validations;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabRequiredSlot;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class CollabValidations {
	
	public void validateOwner(Collab collab, User owner) {
		if (!collab.getOwner().getId().equals(owner.getId())) {
			throw new SoundConnectException(ErrorType.COLLAB_NOT_OWNER);
		}
	}
	
	public void validateNotExpired(Collab collab) {
		if (collab.isDaily()
				&& collab.getExpirationTime() != null
				&& collab.getExpirationTime().isBefore(LocalDateTime.now())) {
			throw new SoundConnectException(ErrorType.COLLAB_EXPIRED);
		}
	}
	
	/**
	 * İlgili enstrümana ait slot'u getirir.
	 * Yoksa COLLAB_SLOT_NOT_REQUIRED fırlatır.
	 */
	public CollabRequiredSlot getRequiredSlot(Collab collab, Instrument instrument) {
		return collab.getRequiredSlots().stream()
		             .filter(slot -> slot.getInstrument().getId().equals(instrument.getId()))
		             .findFirst()
		             .orElseThrow(() -> new SoundConnectException(ErrorType.COLLAB_SLOT_NOT_REQUIRED));
	}
}