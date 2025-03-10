package com.berkayb.soundconnect.repository;

import com.berkayb.soundconnect.entity.Instrument;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InstrumentRepository extends JpaRepository<Instrument, Long> {
}