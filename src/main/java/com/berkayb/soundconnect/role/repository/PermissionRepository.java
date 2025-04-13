package com.berkayb.soundconnect.role.repository;

import com.berkayb.soundconnect.role.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {

}