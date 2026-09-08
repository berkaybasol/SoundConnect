package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BandEntityIdentityTest {
    @Test
    void changingTheInverseConnectionCannotStrandTheOwningVenueLink() {
        Band band = Band.builder().id(UUID.randomUUID()).name("Band").build();
        Venue venue = Venue.builder().id(UUID.randomUUID()).name("Venue").build();
        venue.getActiveBands().add(band);
        band.getActiveVenues().add(venue);

        assertThat(venue.getActiveBands().remove(band)).isTrue();
        assertThat(venue.getActiveBands()).isEmpty();
    }

    @Test
    void renamingTheBandDoesNotStrandExistingVenueLinks() {
        Band band = Band.builder().id(UUID.randomUUID()).name("Old name").build();
        Set<Band> bands = new HashSet<>(Set.of(band));

        band.setName("New name");
        band.setDescription("Updated profile");

        assertThat(bands.remove(band)).isTrue();
    }

    @Test
    void separateTransientBandsDoNotCollapseAndIdAssignmentKeepsTheirBucket() {
        Band first = Band.builder().name("Draft").build();
        Band second = Band.builder().name("Draft").build();
        Set<Band> bands = new HashSet<>();
        bands.add(first);
        bands.add(second);
        assertThat(bands).hasSize(2);

        first.setId(UUID.randomUUID());

        assertThat(bands.remove(first)).isTrue();
        assertThat(bands).containsExactly(second);
    }

    @Test
    void twoSnapshotsOfTheSameBandHaveTheSamePersistentIdentity() {
        UUID id = UUID.randomUUID();
        Band first = Band.builder().id(id).name("Old name").build();
        Band second = Band.builder().id(id).name("New name").build();

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first).isNotEqualTo(Band.builder().id(UUID.randomUUID()).name("Old name").build());
    }

    @Test
    void invitationAndTitleTransitionsCannotStrandMembersInTheirBand() {
        BandMember member = BandMember.builder().id(UUID.randomUUID())
                .status(BandMemberShipStatus.PENDING).bandRole(BandRole.MEMBER)
                .invitationId(UUID.randomUUID()).build();
        Set<BandMember> members = new HashSet<>(Set.of(member));

        member.setStatus(BandMemberShipStatus.ACTIVE);
        member.setMemberTitle("Drummer");
        member.setTitleVersion(1);
        assertThat(members).contains(member);

        member.setStatus(BandMemberShipStatus.LEFT);
        member.setInvitationId(UUID.randomUUID());
        member.setTitleVersion(2);
        member.setMemberTitle(null);
        assertThat(members.remove(member)).isTrue();
    }

    @Test
    void separateTransientMembersDoNotCollapseAndIdAssignmentKeepsTheirBucket() {
        BandMember first = BandMember.builder().status(BandMemberShipStatus.ACTIVE).bandRole(BandRole.FOUNDER).build();
        BandMember second = BandMember.builder().status(BandMemberShipStatus.ACTIVE).bandRole(BandRole.FOUNDER).build();
        Set<BandMember> members = new HashSet<>();
        members.add(first);
        members.add(second);
        assertThat(members).hasSize(2);

        first.setId(UUID.randomUUID());

        assertThat(members.remove(first)).isTrue();
    }

    @Test
    void memberIdentityDoesNotDependOnTheLoadedTenureSnapshot() {
        UUID id = UUID.randomUUID();
        BandMember first = BandMember.builder().id(id).status(BandMemberShipStatus.PENDING).titleVersion(1).build();
        BandMember second = BandMember.builder().id(id).status(BandMemberShipStatus.ACTIVE).titleVersion(2).build();

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first).isNotEqualTo(BandMember.builder().id(UUID.randomUUID()).status(BandMemberShipStatus.PENDING).titleVersion(1).build());
    }
}
