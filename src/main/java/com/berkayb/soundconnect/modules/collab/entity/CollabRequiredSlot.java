package com.berkayb.soundconnect.modules.collab.entity;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import com.berkayb.soundconnect.shared.entity.BaseEntity;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
@Getter
@Setter
@Entity
@Table(name = "tbl_collab_required_slot")
public class CollabRequiredSlot extends BaseEntity {
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "collab_id", nullable = false)
	private Collab collab;
	
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "instrument_id", nullable = false)
	private Instrument instrument;
	
	// ayni enstrumandan kac kisi araniyor? orn 2 gitarist gerekebilir gibi
	@Column(nullable = false)
	private int requiredCount;
	
	@Column(nullable = false)
	private int filledCount; // kaci bulundu?
	
	public boolean hasOpenSlot() {
		return filledCount < requiredCount;
	}
	
	public void fill() {
		if (filledCount >= requiredCount) {
			throw new SoundConnectException(ErrorType.COLLAB_SLOT_ALREADY_FULL);
		}
		filledCount++;
	}
	
	public void unfill() {
		if (filledCount <= 0) {
			throw new SoundConnectException(ErrorType.COLLAB_SLOT_ALREADY_EMPTY);
		}
		filledCount--;
	}
	
}