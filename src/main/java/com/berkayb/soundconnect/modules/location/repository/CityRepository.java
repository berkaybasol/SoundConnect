package com.berkayb.soundconnect.modules.location.repository;

import com.berkayb.soundconnect.modules.location.entity.City;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface CityRepository extends JpaRepository<City, UUID> {
	boolean existsByName(String name);

}