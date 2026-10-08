package com.berkayb.soundconnect.modules.overthinking.repository;

import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OverthinkingPostRepository extends JpaRepository<OverthinkingPost, UUID> {
	String MAINSTAGE = com.berkayb.soundconnect.modules.overthinking.support.OverthinkingMainstageVisibility.SQL;

	@Query(value = "select post.id from tbl_overthinking_post post where post.id in (:ids) and " + MAINSTAGE,
			nativeQuery = true)
	List<UUID> findMainstageVisibleIds(@Param("ids") Collection<UUID> ids);

	@Query(value = "select post.* from tbl_overthinking_post post where " + MAINSTAGE + """
			 and (cast(:authorId as uuid) is null or post.author_id=:authorId)
			 and (cast(:artistId as uuid) is null or post.artist_id=:artistId)
			 order by case when :oldest then post.created_at end asc,
			          case when :oldest then post.id end asc,
			          post.created_at desc, post.id desc
			""", countQuery = "select count(*) from tbl_overthinking_post post where " + MAINSTAGE + """
			 and (cast(:authorId as uuid) is null or post.author_id=:authorId)
			 and (cast(:artistId as uuid) is null or post.artist_id=:artistId)
			""", nativeQuery = true)
	Page<OverthinkingPost> findMainstagePage(@Param("authorId") UUID authorId, @Param("artistId") UUID artistId,
			@Param("oldest") boolean oldest, Pageable pageable);

	@Query(value = """
			select post.* from tbl_overthinking_post post left join (
			 select target_id,count(*) as likes from tbl_like where target_type='OVERTHINKING' group by target_id
			) engagement on engagement.target_id=post.id where
			""" + MAINSTAGE + " order by coalesce(engagement.likes,0) desc, post.created_at desc, post.id desc",
			countQuery = "select count(*) from tbl_overthinking_post post where " + MAINSTAGE, nativeQuery = true)
	Page<OverthinkingPost> findMainstageMostLiked(Pageable pageable);
	/** All post/reveal mutations acquire the post lock before any reveal row lock. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select post from OverthinkingPost post where post.id = :postId")
	Optional<OverthinkingPost> findByIdForUpdate(@Param("postId") UUID postId);

	Page<OverthinkingPost> findByAuthorId(UUID authorId, Pageable pageable);
	
	Page<OverthinkingPost> findByArtistId(UUID artistId, Pageable pageable);

	@Query("select post from OverthinkingPost post join fetch post.author where post.id in :ids")
	List<OverthinkingPost> findForViewerProjection(@Param("ids") Collection<UUID> ids);

	@Query(value = """
			select post.* from tbl_overthinking_post post
			left join (
			    select target_id, count(*) as likes from tbl_like
			    where target_type = 'OVERTHINKING' group by target_id
			) engagement on engagement.target_id = post.id
			order by coalesce(engagement.likes, 0) desc, post.created_at desc, post.id desc
			""", countQuery = "select count(*) from tbl_overthinking_post", nativeQuery = true)
	Page<OverthinkingPost> findMostLiked(Pageable pageable);
}
