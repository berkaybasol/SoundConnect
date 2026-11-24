package com.berkayb.soundconnect.modules.overthinking.repository;

import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingRevealRequest;
import com.berkayb.soundconnect.modules.overthinking.enums.OverthinkingRevealRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface OverthinkingRevealRequestRepository extends JpaRepository<OverthinkingRevealRequest, UUID> {

	/** Scalar lookup avoids caching a stale request before its parent lock is acquired. */
	@Query("select request.post.id from OverthinkingRevealRequest request where request.id = :requestId and request.author.id = :authorId")
	Optional<UUID> findPostIdByIdAndAuthorId(@Param("requestId") UUID requestId, @Param("authorId") UUID authorId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from OverthinkingRevealRequest request where request.id = :requestId and request.author.id = :authorId")
	Optional<OverthinkingRevealRequest> findByIdAndAuthorIdForUpdate(@Param("requestId") UUID requestId, @Param("authorId") UUID authorId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select request from OverthinkingRevealRequest request where request.post.id = :postId and request.requester.id = :requesterId")
	Optional<OverthinkingRevealRequest> findByPostIdAndRequesterIdForUpdate(@Param("postId") UUID postId, @Param("requesterId") UUID requesterId);

	/** Caller holds the parent post write lock, preventing concurrent request insertion. */
	@Modifying(flushAutomatically = true)
	@Query("delete from OverthinkingRevealRequest request where request.post.id = :postId")
	void deleteByPostId(@Param("postId") UUID postId);
	
	boolean existsByPostIdAndRequesterId(UUID postId, UUID requesterId);
	
	boolean existsByPostIdAndRequesterIdAndStatus(
			UUID postId,
			UUID requesterId,
			OverthinkingRevealRequestStatus status
	);
	
	@EntityGraph(attributePaths = {"post", "requester", "author"})
	Optional<OverthinkingRevealRequest> findByPostIdAndRequesterId(UUID postId, UUID requesterId);
	
	@EntityGraph(attributePaths = {"post", "requester", "author"})
	Optional<OverthinkingRevealRequest> findByIdAndAuthorId(UUID requestId, UUID authorId);
	
	String INCOMING = """
            from OverthinkingRevealRequest r where r.author.id=:authorId and r.deliverySuppressed=false
              and not exists (select 1 from UserBlock b where b.unblockedAt is null and
                 ((b.blocker.id=:authorId and b.blocked.id=r.requester.id)
                   or (b.blocked.id=:authorId and b.blocker.id=r.requester.id)))
            """;
    @EntityGraph(attributePaths = {"post", "requester", "author"})
    @Query("select r " + INCOMING)
    Page<OverthinkingRevealRequest> findByAuthorIdOrderByCreatedAtDesc(@Param("authorId") UUID authorId, Pageable pageable);

    @Query("""
            select r.id from OverthinkingRevealRequest r where r.requester.id=:viewer and r.id in :ids
              and exists (select 1 from UserBlock b where b.unblockedAt is null and
                ((b.blocker.id=:viewer and b.blocked.id=r.author.id)
                  or (b.blocked.id=:viewer and b.blocker.id=r.author.id)))
            """)
    Set<UUID> blockedRequestIds(@Param("viewer") UUID viewer, @Param("ids") Collection<UUID> ids);

	/** Uses the existing author/status index without fetching request or profile data. */
	@Query("select count(r) " + INCOMING + " and r.status=:status")
    long countByAuthorIdAndStatus(@Param("authorId") UUID authorId, @Param("status") OverthinkingRevealRequestStatus status);
	
	@EntityGraph(attributePaths = {"post", "requester", "author"})
	Page<OverthinkingRevealRequest> findByRequesterIdOrderByCreatedAtDesc(UUID requesterId, Pageable pageable);
	
	@Query("""
	select r.post.id
	from OverthinkingRevealRequest r
	where r.requester.id = :requesterId
	  and r.status = :status
	  and r.post.id in :postIds
	""")
	Set<UUID> findPostIdsByRequesterIdAndStatusAndPostIdIn(
			@Param("requesterId") UUID requesterId,
			@Param("status") OverthinkingRevealRequestStatus status,
			@Param("postIds") Collection<UUID> postIds
	);
}
