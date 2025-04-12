package com.berkayb.soundconnect.follow.repository;

import com.berkayb.soundconnect.follow.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FollowRepository extends JpaRepository<Follow, Long> {
}