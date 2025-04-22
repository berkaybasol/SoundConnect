package com.berkayb.soundconnect.location.repository;

import com.berkayb.soundconnect.location.entity.City;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

public interface CityRepository extends JpaRepository<City, UUID> {
	boolean existsByName(String name);

}