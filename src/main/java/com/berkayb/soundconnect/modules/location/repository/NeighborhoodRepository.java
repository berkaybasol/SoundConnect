package com.berkayb.soundconnect.modules.location.repository;

import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface NeighborhoodRepository extends JpaRepository<Neighborhood, UUID> {
	
	boolean existsByNameAndDistrict_Id(String name, UUID districtId);
	
	
	List<Neighborhood> findAllByDistrict_Id(UUID districtId);
}