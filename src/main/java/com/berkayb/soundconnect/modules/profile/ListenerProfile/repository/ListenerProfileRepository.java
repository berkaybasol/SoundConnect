package com.berkayb.soundconnect.modules.profile.ListenerProfile.repository;

import com.berkayb.soundconnect.modules.profile.ListenerProfile.entity.ListenerProfile;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public interface ListenerProfileRepository extends JpaRepository<ListenerProfile, UUID> {
	Optional<ListenerProfile> findByUserId(UUID userId);

	boolean existsByUserIdAndVisibilityMode(UUID userId, ListenerVisibilityMode visibilityMode);

	boolean existsByUserIdAndVisibilityChoiceCompletedTrue(UUID userId);

	/**
	 * Finds listener accounts whose public identity is not yet eligible for
	 * contextual projection. The missing-profile branch is intentional: legacy
	 * or partially provisioned listener accounts must fail closed as well.
	 */
	@Query("""
			select distinct user.id
			from User user
			join user.roles role
			where user.id in :userIds
			  and role.name = 'ROLE_LISTENER'
			  and not exists (
				select profile.id
				from ListenerProfile profile
				where profile.user = user
				  and profile.visibilityChoiceCompleted = true
			  )
			""")
	Set<UUID> findUserIdsRequiringVisibilityChoice(
			@Param("userIds") Collection<UUID> userIds
	);

	boolean existsByIdAndVisibilityMode(UUID profileId, ListenerVisibilityMode visibilityMode);

	@Query("""
			select lp.user.id
			from ListenerProfile lp
			where lp.user.id in :userIds
			  and lp.visibilityMode = :visibilityMode
			""")
	Set<UUID> findUserIdsByVisibilityMode(
			@Param("userIds") Collection<UUID> userIds,
			@Param("visibilityMode") ListenerVisibilityMode visibilityMode
	);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select lp from ListenerProfile lp where lp.user.id = :userId")
	Optional<ListenerProfile> findByUserIdForUpdate(@Param("userId") UUID userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select lp from ListenerProfile lp where lp.id = :profileId")
	Optional<ListenerProfile> findByIdForUpdate(@Param("profileId") UUID profileId);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@EntityGraph(attributePaths = "user")
	@Query("select lp from ListenerProfile lp where lp.user.id = :userId")
	Optional<ListenerProfile> findByUserIdForVisibilityRead(@Param("userId") UUID userId);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@EntityGraph(attributePaths = "user")
	@Query("select lp from ListenerProfile lp where lp.id = :profileId")
	Optional<ListenerProfile> findByIdForVisibilityRead(@Param("profileId") UUID profileId);

	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select lp from ListenerProfile lp where lp.user.id in :userIds order by lp.id")
	List<ListenerProfile> findAllByUserIdInForVisibilityRead(
			@Param("userIds") Collection<UUID> userIds
	);
	
	@EntityGraph(attributePaths = "user")
	@Query("""
        select lp
        from ListenerProfile lp
        where
            (:q is null or trim(:q) = '')
            or locate(lower(:q), lower(coalesce(lp.name, ''))) > 0
            or (:usernameQuery <> ''
                and locate(:usernameQuery, coalesce(lp.user.username, '')) > 0)
            or locate(lower(:q), lower(coalesce(lp.description, ''))) > 0
        order by
            case
                when lower(coalesce(lp.name, lp.user.username, '')) = lower(:q) then 0
                when locate(lower(:q), lower(coalesce(lp.name, lp.user.username, ''))) = 1 then 1
                else 2
            end,
            lower(coalesce(lp.name, lp.user.username, '')),
            lp.id
    """)
	List<ListenerProfile> searchByUsernameOrBio(
			@Param("q") String q,
			@Param("usernameQuery") String usernameQuery,
			Pageable pageable
	);

	/**
	 * Public discovery excludes listeners whose first visibility choice is still
	 * pending. Completed ghost listeners remain reachable by their stable username,
	 * while hidden profile copy cannot match or rank their result.
	 *
	 * <p>The null mode branch is intentional for rolling deployments over completed
	 * legacy rows; null has the same externally visible semantics as STANDARD.</p>
	 */
	@EntityGraph(attributePaths = "user")
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("""
			select lp
			from ListenerProfile lp
			where lp.visibilityChoiceCompleted = true
			  and (
				(:usernameQuery <> ''
					and locate(:usernameQuery, coalesce(lp.user.username, '')) > 0)
				or (
					(lp.visibilityMode is null or lp.visibilityMode <> :ghostMode)
					and (
						locate(lower(:q), lower(coalesce(lp.name, ''))) > 0
						or locate(lower(:q), lower(coalesce(lp.description, ''))) > 0
					)
				)
			  )
			order by
				case
					when lower(
						case when lp.visibilityMode = :ghostMode
							then coalesce(lp.user.username, '')
							else coalesce(lp.name, lp.user.username, '')
						end
					) = lower(:q) then 0
					when locate(
						lower(:q),
						lower(
							case when lp.visibilityMode = :ghostMode
								then coalesce(lp.user.username, '')
								else coalesce(lp.name, lp.user.username, '')
							end
						)
					) = 1 then 1
					else 2
				end,
				lower(
					case when lp.visibilityMode = :ghostMode
						then coalesce(lp.user.username, '')
						else coalesce(lp.name, lp.user.username, '')
					end
				),
				lp.id
			""")
	List<ListenerProfile> searchForPublicDiscovery(
			@Param("q") String q,
			@Param("usernameQuery") String usernameQuery,
			@Param("ghostMode") ListenerVisibilityMode ghostMode,
			Pageable pageable
	);

	@EntityGraph(attributePaths = "user")
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("""
			select lp from ListenerProfile lp
			where lp.user.id = :userId
			  and lp.visibilityChoiceCompleted = true
			""")
	Optional<ListenerProfile> findForPublicIdentityByUserId(@Param("userId") UUID userId);
}
