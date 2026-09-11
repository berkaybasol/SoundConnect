package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class MusicianFeedViewerGuard {
    private final UserRepository users;
    private final MusicianProfileRepository musicians;

    public MusicianFeedViewerGuard(UserRepository users, MusicianProfileRepository musicians) {
        this.users = users;
        this.musicians = musicians;
    }

    public UUID requireMusicianProfile(UUID userId) {
        if (userId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        Set<String> roles = users.findRoleNamesByUserId(userId);
        if (!roles.contains("ROLE_MUSICIAN")
                || !users.findExistingPersonalProfileRoleNames(userId).equals(Set.of("ROLE_MUSICIAN"))) {
            throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
        }
        return musicians.findByUserId(userId).map(value -> value.getId())
                .orElseThrow(() -> new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
    }
}
