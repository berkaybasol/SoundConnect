package com.berkayb.soundconnect.repository;

import com.berkayb.soundconnect.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
}