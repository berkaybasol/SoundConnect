package com.berkayb.soundconnect.modules.collab.support.validations;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
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
	
	public void validateRequired(Collab collab, Instrument instrument) {
		if (!collab.getRequiredInstruments().contains(instrument)) {
			throw new SoundConnectException(ErrorType.COLLAB_SLOT_NOT_REQUIRED);
		}
	}
	
	public void validateNotAlreadyFilled(Collab collab, Instrument instrument) {
		if (collab.getFilledInstruments().contains(instrument)) {
			throw new SoundConnectException(ErrorType.COLLAB_SLOT_ALREADY_FILLED);
		}
	}
	
	public void validateFilled(Collab collab, Instrument instrument) {
		if (!collab.getFilledInstruments().contains(instrument)) {
			throw new SoundConnectException(ErrorType.COLLAB_SLOT_NOT_FILLED);
		}
	}
	
	public void validateNotExpired(Collab collab) {
		if (collab.isDaily() && collab.getExpirationTime() != null && collab.getExpirationTime()
		                                                                    .isBefore(LocalDateTime.now())) {
			throw new SoundConnectException(ErrorType.COLLAB_EXPIRED);
		}
	}
}