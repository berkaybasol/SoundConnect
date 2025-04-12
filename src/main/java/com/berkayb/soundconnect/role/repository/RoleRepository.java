package com.berkayb.soundconnect.role.repository;

import com.berkayb.soundconnect.role.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleRepository extends JpaRepository<Role, Long> {
}