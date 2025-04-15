package com.berkayb.soundconnect.instrument.service;

import com.berkayb.soundconnect.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.instrument.entity.Instrument;

public interface IInstrumentService {
	Instrument saveInstrument(InstrumentSaveRequestDto instrument);
}