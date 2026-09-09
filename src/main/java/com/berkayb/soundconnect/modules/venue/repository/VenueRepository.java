package com.berkayb.soundconnect.modules.venue.repository;

import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.projection.TableGroupVenueOptionProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VenueRepository extends JpaRepository<Venue, UUID> {
	/** Public profiles, directories and connection admission share this eligibility rule. */
	String PUBLIC_VISIBILITY = """
			venue.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
			and venue.owner.status = com.berkayb.soundconnect.modules.user.enums.UserStatus.ACTIVE
			and venue.owner.emailVerified = true
			and venue.district.city.id = venue.city.id
			and venue.neighborhood.district.id = venue.district.id
			""";

	@EntityGraph(attributePaths = {"owner", "city", "district", "neighborhood"})
	@Query("select venue from Venue venue where venue.id = :venueId and " + PUBLIC_VISIBILITY)
	Optional<Venue> findPubliclyVisibleById(@Param("venueId") UUID venueId);

	@Query("select (count(venue) > 0) from Venue venue where venue.id = :venueId and " + PUBLIC_VISIBILITY)
	boolean existsPubliclyVisibleById(@Param("venueId") UUID venueId);

	@EntityGraph(attributePaths = {"owner", "city", "district", "neighborhood"})
	@Query("select venue from Venue venue where " + PUBLIC_VISIBILITY + " order by lower(venue.name), venue.id")
	List<Venue> findAllPubliclyVisible();

	@EntityGraph(attributePaths = {"owner", "city", "district", "neighborhood"})
	@Query("select venue from Venue venue where venue.owner.id = :ownerId and " + PUBLIC_VISIBILITY
			+ " order by lower(venue.name), venue.id")
	List<Venue> findAllPubliclyVisibleByOwnerId(@Param("ownerId") UUID ownerId);

List<Venue> findAllByOwnerId(UUID ownerId);
Optional<Venue> findByIdAndOwnerId(UUID venueId, UUID ownerId);
boolean existsByOwner_Id(UUID ownerId);

	/** See MusicianProfileRepository#lockActiveVenueConnection. */
	@Query(value = """
			select 1
			from venue_active_bands connection
			where connection.venue_id = :venueId
			  and connection.band_id = :bandId
			for key share
			""", nativeQuery = true)
	Optional<Integer> lockActiveBandConnection(
			@Param("venueId") UUID venueId,
			@Param("bandId") UUID bandId
	);

	@Query("""
			select
				v.id as id,
				v.name as name,
				vp.profilePictureMediaId as profilePictureMediaId,
				v.address as address,
				c.id as cityId,
				c.name as cityName,
				d.id as districtId,
				d.name as districtName,
				n.id as neighborhoodId,
				n.name as neighborhoodName
			from Venue v
			join v.city c
			join v.district d
			join v.neighborhood n
			left join VenueProfile vp on vp.venue = v
			where v.status = :status
			  and d.city = c
			  and n.district = d
			  and locate(lower(:q), lower(v.name)) > 0
			order by
				case
					when lower(v.name) = lower(:q) then 0
					when locate(lower(:q), lower(v.name)) = 1 then 1
					else 2
				end,
				lower(v.name),
				lower(c.name),
				lower(d.name),
				lower(n.name),
				lower(v.address),
				v.id
			""")
	List<TableGroupVenueOptionProjection> searchTableGroupVenueOptions(
			@Param("q") String q,
			@Param("status") VenueStatus status,
			Pageable pageable
	);

	@EntityGraph(attributePaths = {"owner", "city", "district", "neighborhood"})
	@Query(
			value = """
					select v from Venue v
					where v.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
					and v.owner.status = com.berkayb.soundconnect.modules.user.enums.UserStatus.ACTIVE
					and v.owner.emailVerified = true
					and v.district.city.id = v.city.id and v.neighborhood.district.id = v.district.id
					and locate(lower(:q), lower(coalesce(v.name, ''))) > 0
					order by lower(v.name), v.id
					""",
			countQuery = """
					select count(v) from Venue v
					where v.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
					and v.owner.status = com.berkayb.soundconnect.modules.user.enums.UserStatus.ACTIVE
					and v.owner.emailVerified = true
					and v.district.city.id = v.city.id and v.neighborhood.district.id = v.district.id
					and locate(lower(:q), lower(coalesce(v.name, ''))) > 0
					"""
	)
	Page<Venue> searchByName(@Param("q") String q, Pageable pageable);
	
	
	Page<Venue> findByNameContainingIgnoreCase(String name, Pageable pageable);

	@EntityGraph(attributePaths = {"owner", "city", "district"})
	@Query("""
			select v
			from Venue v
			where v.status = com.berkayb.soundconnect.modules.venue.enums.VenueStatus.APPROVED
			   and v.owner.status = com.berkayb.soundconnect.modules.user.enums.UserStatus.ACTIVE
			   and v.owner.emailVerified = true
			   and v.district.city.id = v.city.id and v.neighborhood.district.id = v.district.id
			   and (locate(lower(:q), lower(coalesce(v.name, ''))) > 0
			   or (:usernameQuery <> ''
			       and locate(:usernameQuery, coalesce(v.owner.username, '')) > 0))
			order by
				case
					when lower(coalesce(v.name, v.owner.username, '')) = lower(:q) then 0
					when locate(lower(:q), lower(coalesce(v.name, v.owner.username, ''))) = 1 then 1
					else 2
				end,
				lower(coalesce(v.name, v.owner.username, '')),
				v.id
			""")
	Page<Venue> searchByNameOrOwnerUsername(
			@Param("q") String q,
			@Param("usernameQuery") String usernameQuery,
			Pageable pageable
	);
}
