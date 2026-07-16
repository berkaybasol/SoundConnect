package com.berkayb.soundconnect.modules.role.repository;

import com.berkayb.soundconnect.modules.role.entity.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface RoleRepository extends JpaRepository<Role, UUID> {
	Optional<Role> findByName(String name);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select role from Role role where role.name = :name")
	Optional<Role> findByNameForUpdate(@Param("name") String name);
}
