package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BandMemberRepository extends JpaRepository<BandMember, UUID> {

	@Query(value = """
		select new com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandReceivedInvitationRow(
			b.id, b.name, b.profilePictureMediaId, m.invitationId)
		from BandMember m join m.band b
		where m.user.id = :userId and m.status = :status
		order by coalesce(m.updatedAt, m.createdAt) desc nulls last, m.id desc
		""", countQuery = """
		select count(m) from BandMember m join m.band b
		where m.user.id = :userId and m.status = :status
		""")
	Page<BandReceivedInvitationRow> findReceivedInvitationSummaries(
			@Param("userId") UUID userId,
			@Param("status") BandMemberShipStatus status,
			Pageable pageable);

	@Query("""
		select new com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandReceivedInvitationRow(
			b.id, b.name, b.profilePictureMediaId, m.invitationId)
		from BandMember m join m.band b
		where m.band.id = :bandId and m.user.id = :userId and m.status = :status
		""")
	Optional<BandReceivedInvitationRow> findCurrentReceivedInvitation(
			@Param("bandId") UUID bandId, @Param("userId") UUID userId,
			@Param("status") BandMemberShipStatus status);

	// Scalar projection avoids materializing user credentials/roles or the band's
	// complete roster. The band/user unique index scopes both content and count.
	@Query(value = """
		select new com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandPendingInvitationRow(
			u.id, u.username, musician.profilePictureMediaId)
		from BandMember m join m.user u left join u.musicianProfile musician
		where m.band.id = :bandId and m.status = :status
		order by coalesce(m.updatedAt, m.createdAt) desc nulls last, m.id desc
		""", countQuery = """
		select count(m) from BandMember m
		where m.band.id = :bandId and m.status = :status
		""")
	Page<BandPendingInvitationRow> findPendingInvitationSummaries(
			@Param("bandId") UUID bandId,
			@Param("status") BandMemberShipStatus status,
			Pageable pageable);
	
	// ayni kullanici bir bandin icinde yalnizca bir kere bulunmali.
	// band ve user idsine gore uyelik bulmak icin
	Optional<BandMember> findByBandIdAndUserId(UUID bandId, UUID userId);
	
	
	// belirli bir bandin butun uyelerini getirir.
	List<BandMember> findByBandId(UUID bandId);
	
	// belirli bir kullanicin tum band uyeliklerini getirir
	List<BandMember> findByUserId(UUID userId);
	
	List<BandMember> findByUserIdAndStatus(UUID userId, BandMemberShipStatus status);

	@EntityGraph(attributePaths = "band")
	List<BandMember> findByUserIdAndStatusAndBandRole(
			UUID userId,
			BandMemberShipStatus status,
			BandRole bandRole
	);

	@EntityGraph(attributePaths = "band")
	Optional<BandMember> findByBandIdAndUserIdAndStatusAndBandRole(
			UUID bandId,
			UUID userId,
			BandMemberShipStatus status,
			BandRole bandRole
	);

	long countByUserIdAndStatus(UUID userId, BandMemberShipStatus status);
	long countByUserIdAndStatusAndBandRole(UUID userId, BandMemberShipStatus status, BandRole bandRole);
	
	boolean existsByUser_IdAndStatus(UUID userId, BandMemberShipStatus status);
}
