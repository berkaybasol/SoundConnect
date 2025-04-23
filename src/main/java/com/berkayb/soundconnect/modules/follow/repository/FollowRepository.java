package com.berkayb.soundconnect.modules.follow.repository;

import com.berkayb.soundconnect.modules.follow.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface FollowRepository extends JpaRepository<Follow, UUID> {
}