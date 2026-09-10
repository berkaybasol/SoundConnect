package com.berkayb.soundconnect.modules.user.repository;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
	@Query("select u.id from User u where u.id in :ids and u.erasedAt is not null")
	Set<UUID> findErasedIds(@Param("ids") java.util.Collection<UUID> ids);

	Optional<User> findByUsername(String username);
	boolean existsByUsername(String username);
	boolean existsByUsernameAndIdNot(String username, UUID id);
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

	/**
	 * Lightweight authorization projection that deliberately does not attach a
	 * User entity to the persistence context before a later locked read.
	 */
	@Query("select r.name from User u join u.roles r where u.id = :id")
	Set<String> findRoleNamesByUserId(@Param("id") UUID id);

	/**
	 * Reads the personal profile aggregates that actually exist for an account.
	 * Role rows alone are insufficient for legacy/corrupt data because a stale
	 * role can be missing while its profile aggregate remains reachable.
	 */
	@Query(value = """
			select distinct personal_profile_role
			from (
				select 'ROLE_LISTENER' as personal_profile_role
				from "tbl_listener-profile" where user_id = :userId
				union all
				select 'ROLE_MUSICIAN'
				from tbl_musician_profile where user_id = :userId
				union all
				select 'ROLE_STUDIO'
				from tbl_studio_profile where user_id = :userId
				union all
				select 'ROLE_ORGANIZER'
				from tbl_organizer_profile where user_id = :userId
				union all
				select 'ROLE_PRODUCER'
				from tbl_producer_profile where user_id = :userId
				union all
				select 'ROLE_VENUE'
				from tbl_venues where owner_id = :userId
			) personal_profiles
			""", nativeQuery = true)
	Set<String> findExistingPersonalProfileRoleNames(@Param("userId") UUID userId);

	long countDistinctByRoles_Name(String roleName);

}
