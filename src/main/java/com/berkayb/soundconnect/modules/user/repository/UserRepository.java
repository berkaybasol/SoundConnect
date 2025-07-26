package com.berkayb.soundconnect.modules.user.repository;

import com.berkayb.soundconnect.modules.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
Optional <User> findByUsername(String username);
boolean existsByUsername(String username);
boolean existsByEmail(String email);
Optional<User> findByEmailVerificationToken(String token);
Optional<User> findByEmail(String email);

}