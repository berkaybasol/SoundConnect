package com.berkayb.soundconnect.instrument.repository;

import com.berkayb.soundconnect.instrument.entity.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {
}