package com.berkayb.soundconnect.service;

import com.berkayb.soundconnect.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.entity.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IInstrumentService {
	Instrument saveInstrument(InstrumentSaveRequestDto instrument);
}