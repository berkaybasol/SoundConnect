package com.berkayb.soundconnect.modules.overthinking.repository;

import com.berkayb.soundconnect.modules.overthinking.entity.OverthinkingPost;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OverthinkingPostRepository extends JpaRepository<OverthinkingPost, UUID> {
	Page<OverthinkingPost> findByAuthorId(UUID authorId, Pageable pageable);
	
	Page<OverthinkingPost> findByArtistId(UUID artistId, Pageable pageable);
}