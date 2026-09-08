package com.berkayb.soundconnect.modules.application.artistvenuelinkapplication.service;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ArtistVenueConnectionIdentityTest {
    @Test
    void distinctMusiciansWithTheSameDisplayFieldsRemainDistinctVenueMembers() {
        MusicianProfile first = musician(), second = musician();
        Venue venue = Venue.builder().id(UUID.randomUUID()).name("Shared venue").build();
        first.getActiveVenues().add(venue);
        second.getActiveVenues().add(venue);
        venue.getActiveMusicians().add(first);
        venue.getActiveMusicians().add(second);

        assertThat(first).isNotEqualTo(second);
        assertThat(venue.getActiveMusicians()).hasSize(2);
    }

    @Test
    void profileAndConnectionEditsDoNotStrandAnEntityInItsOriginalHashBucket() {
        MusicianProfile musician = musician();
        Venue venue = Venue.builder().id(UUID.randomUUID()).name("Venue").build();
        musician.getActiveVenues().add(venue);
        venue.getActiveMusicians().add(musician);
        musician.setStageName("Renamed musician");
        musician.getActiveVenues().remove(venue);

        assertThat(venue.getActiveMusicians().remove(musician)).isTrue();
        assertThat(venue.getActiveMusicians()).isEmpty();
    }

    @Test
    void equalityUsesPersistentIdentityWithoutCollapsingUnsavedProfiles() {
        MusicianProfile original = musician();
        MusicianProfile sameIdentity = musician();
        sameIdentity.setId(original.getId());
        sameIdentity.setStageName("Different loaded snapshot");
        assertThat(original).isEqualTo(sameIdentity);
        assertThat(sameIdentity).isEqualTo(original);
        assertThat(original.hashCode()).isEqualTo(sameIdentity.hashCode());

        MusicianProfile unsaved = MusicianProfile.builder().build();
        MusicianProfile otherUnsaved = MusicianProfile.builder().build();
        assertThat(unsaved).isNotEqualTo(otherUnsaved);
        Set<MusicianProfile> beforePersist = new HashSet<>(Set.of(unsaved));
        unsaved.setId(UUID.randomUUID());
        assertThat(beforePersist.remove(unsaved)).isTrue();
    }

    private MusicianProfile musician() {
        return MusicianProfile.builder().id(UUID.randomUUID())
                .user(User.builder().id(UUID.randomUUID()).build()).stageName("Same stage name").build();
    }
}
