package com.berkayb.soundconnect.modules.instrument.repository;

import com.berkayb.soundconnect.modules.instrument.entity.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface InstrumentRepository extends JpaRepository<Instrument, UUID> {
}