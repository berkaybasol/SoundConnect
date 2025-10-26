package com.berkayb.soundconnect.modules.tablegroup.repository;

import com.berkayb.soundconnect.modules.tablegroup.entity.TableGroup;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import jakarta.persistence.Table;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.UUID;

public interface TableGroupRepository extends JpaRepository<TableGroup, UUID> {

	Page<TableGroup> findByCityIdAndDistrictIdAndNeighborhoodIdAndStatusAndExpiresAtAfter(UUID cityId,
	                                                                                      UUID districtId,
	                                                                                      UUID neighborhoodId,
	                                                                                      TableGroupStatus status,
	                                                                                      LocalDateTime expiresAt,
	                                                                                      Pageable pageable);
	
	Page<TableGroup> findByCityIdAndDistrictIdAndStatusAndExpiresAtAfter(UUID cityId, UUID districtId, TableGroupStatus status, LocalDateTime expiresAt, Pageable pageable);
	
	Page<TableGroup> findByCityIdAndStatusAndExpiresAtAfter(UUID cityId, TableGroupStatus status,
	                                                        LocalDateTime expiresAt, Pageable pageable);
}