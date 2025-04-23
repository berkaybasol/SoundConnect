package com.berkayb.soundconnect.modules.instrument.service;

import com.berkayb.soundconnect.modules.instrument.dto.request.InstrumentSaveRequestDto;
import com.berkayb.soundconnect.modules.instrument.entity.Instrument;

public interface IInstrumentService {
	Instrument saveInstrument(InstrumentSaveRequestDto instrument);
}