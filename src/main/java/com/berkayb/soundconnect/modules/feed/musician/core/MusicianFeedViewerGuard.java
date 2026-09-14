package com.berkayb.soundconnect.modules.feed.musician.core;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Comparator;
import java.util.Set;
import java.util.UUID;

@Component
public class MusicianFeedViewerGuard {
    private static final Set<String> PERSONAL_ROLES = Set.of("ROLE_MUSICIAN", "ROLE_LISTENER",
            "ROLE_VENUE", "ROLE_STUDIO", "ROLE_ORGANIZER", "ROLE_PRODUCER");
    private final UserRepository users;
    private final MusicianProfileRepository musicians;
    private final VenueRepository venues;
    private final VenueProfileRepository venueProfiles;
    private final ListenerProfileRepository listeners;

    public MusicianFeedViewerGuard(UserRepository users, MusicianProfileRepository musicians) {
        this(users, musicians, null, null);
    }

    public MusicianFeedViewerGuard(UserRepository users, MusicianProfileRepository musicians,
                                  VenueRepository venues, VenueProfileRepository venueProfiles) {
        this(users, musicians, venues, venueProfiles, null);
    }

    @Autowired
    public MusicianFeedViewerGuard(UserRepository users, MusicianProfileRepository musicians,
                                  VenueRepository venues, VenueProfileRepository venueProfiles,
                                  ListenerProfileRepository listeners) {
        this.users = users;
        this.musicians = musicians;
        this.venues = venues;
        this.venueProfiles = venueProfiles;
        this.listeners = listeners;
    }

    public UUID requireMusicianProfile(UUID userId) {
        requireCanonicalRole(userId, "ROLE_MUSICIAN");
        return musicians.findByUserId(userId).map(value -> value.getId())
                .orElseThrow(() -> new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
    }

    /** The public venue target is venue.id, not the optional profile aggregate ID. */
    public UUID requireVenueProfile(UUID userId) {
        requireCanonicalRole(userId, "ROLE_VENUE");
        if (venues == null || venueProfiles == null) throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
        return venues.findAllPubliclyVisibleByOwnerId(userId).stream()
                .filter(venue -> venue.getId() != null && venue.getOwner() != null
                        && userId.equals(venue.getOwner().getId())
                        && venue.getOwner().getStatus() == UserStatus.ACTIVE
                        && Boolean.TRUE.equals(venue.getOwner().getEmailVerified())
                        && venue.getOwner().getErasedAt() == null)
                .sorted(Comparator.comparing(Venue::getId))
                .filter(venue -> venueProfiles.findByVenueId(venue.getId()).isPresent())
                .map(Venue::getId).findFirst()
                .orElseThrow(() -> new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
    }

    public UUID requireProfile(UUID userId, BackstageFeedAudience audience) {
        if (audience == null) throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
        return switch (audience) {
            case MUSICIAN -> requireMusicianProfile(userId);
            case VENUE -> requireVenueProfile(userId);
            case LISTENER -> requireListenerProfile(userId);
        };
    }

    /** Feed consumption does not publish the viewer's identity, including ghost listeners. */
    public UUID requireListenerProfile(UUID userId) {
        requireCanonicalRole(userId, "ROLE_LISTENER");
        if (listeners == null) throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
        return listeners.findByUserId(userId)
                .filter(profile -> profile.getUser() != null && userId.equals(profile.getUser().getId())
                        && profile.getUser().getStatus() == UserStatus.ACTIVE
                        && Boolean.TRUE.equals(profile.getUser().getEmailVerified())
                        && profile.getUser().getErasedAt() == null)
                .map(profile -> profile.getId())
                .orElseThrow(() -> new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
    }

    private void requireCanonicalRole(UUID userId, String expectedRole) {
        if (userId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        Set<String> roles = users.findRoleNamesByUserId(userId);
        if (!roles.contains(expectedRole)
                || roles.stream().filter(PERSONAL_ROLES::contains).count() != 1
                || !users.findExistingPersonalProfileRoleNames(userId).equals(Set.of(expectedRole))) {
            throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
        }
    }
}
