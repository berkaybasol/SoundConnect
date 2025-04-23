package com.berkayb.soundconnect.modules.role.repository;

import com.berkayb.soundconnect.modules.role.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PermissionRepository extends JpaRepository<Permission, UUID> {
	Optional<Permission> findByName(String name);

}