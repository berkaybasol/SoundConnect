package com.berkayb.soundconnect.modules.user.repository;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.AuthProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
	/** Caller holds the account row lock; never derive the increment from a cached entity. */
	@Modifying
	@Query(value = """
			update tbl_user set password = :password, session_version = session_version + 1,
			updated_at = CURRENT_TIMESTAMP where id = :id
			""", nativeQuery = true)
	int resetPasswordAndRevokeSessions(@Param("id") UUID id, @Param("password") String password);

	@Query("select u.id from User u where u.id in :ids and u.erasedAt is not null")
	Set<UUID> findErasedIds(@Param("ids") java.util.Collection<UUID> ids);

	/**
	 * Scalar handle lookup for an already admitted public DM sender. The caller
	 * must resolve the public profile first in the same transaction, retaining
	 * its listener visibility SHARE lock. This query adds a fresh fail-closed
	 * guard; it does not independently authorize public profile disclosure.
	 */
	@Query(value = """
			select u.user_name from tbl_user u
			where u.id=:userId and u.status='ACTIVE' and u.email_verified=true and u.erased_at is null
			  and not exists (select 1 from "tbl_listener-profile" lp where lp.user_id=u.id
			      and (lp.visibility_choice_completed is not true or lp.visibility_mode is distinct from 'STANDARD'))
			""", nativeQuery = true)
	Optional<String> findPublicUsernameForDmPush(@Param("userId") UUID userId);

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
