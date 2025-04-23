package com.berkayb.soundconnect.modules.location.repository;

import com.berkayb.soundconnect.modules.location.entity.District;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DistrictRepository extends JpaRepository<District, UUID> {
	boolean existsByNameAndCity_Id(String name, UUID cityId);
	List<District> findDistrictsByCity_Id(UUID cityId);
}