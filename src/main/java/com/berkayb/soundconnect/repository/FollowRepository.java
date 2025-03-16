package com.berkayb.soundconnect.repository;

import com.berkayb.soundconnect.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FollowRepository extends JpaRepository<Follow, Long> {
}