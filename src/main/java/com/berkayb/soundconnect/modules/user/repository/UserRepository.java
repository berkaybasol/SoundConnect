package com.berkayb.soundconnect.modules.user.repository;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
	Optional<User> findByUsername(String username);
	boolean existsByUsername(String username);
	boolean existsByEmail(String email);
	boolean existsByEmailAndIdNot(String email, UUID id);
	Optional<User> findByEmailVerificationToken(String token);
	Optional<User> findByEmail(String email);
	Optional<User> findByProviderAndProviderSubject(AuthProvider provider, String providerSubject);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.email = :email")
	Optional<User> findByEmailForUpdate(@Param("email") String email);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(@Param("id") UUID id);

	long countDistinctByRoles_Name(String roleName);

}
