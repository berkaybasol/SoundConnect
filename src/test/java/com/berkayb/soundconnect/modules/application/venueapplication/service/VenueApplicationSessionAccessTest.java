package com.berkayb.soundconnect.modules.application.venueapplication.service;

import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.role.entity.Permission;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VenueApplicationSessionAccessTest {
    private VenueApplicationRepository apps;
    private VenueRepository venues;
    private VenueApplicationSessionAccess access;
    private User user;
    private VenueApplication app;
    @BeforeEach void setup() {
        apps=mock(VenueApplicationRepository.class);venues=mock(VenueRepository.class);access=new VenueApplicationSessionAccess(apps,venues);
        user=User.builder().id(UUID.randomUUID()).status(UserStatus.PENDING_VENUE_REQUEST).emailVerified(true).roles(new HashSet<>()).permissions(new HashSet<>()).build();
        app=VenueApplication.builder().id(UUID.randomUUID()).applicant(user).status(ApplicationStatus.PENDING).build();
        when(apps.findFirstByApplicant_IdOrderByApplicationDateDescCreatedAtDescIdDesc(user.getId())).thenReturn(Optional.of(app));
        when(apps.findByIdAndApplicant_Id(app.getId(),user.getId())).thenReturn(Optional.of(app));
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","REJECTED"})
    void verifiedRolelessCurrentApplicantHasOnlyOwnCurrentApplication(String state) {
        app.setStatus(ApplicationStatus.valueOf(state));
        assertThat(access.findSessionApplication(user)).contains(app.getId());
        assertThat(access.isAccessible(user,app.getId())).isTrue();
        assertThat(access.isAccessible(user,UUID.randomUUID())).isFalse();
    }
    @ParameterizedTest @ValueSource(strings={"unverified","erased","inactive","studio","rejectedStudio","listener","venueRole","directPermission","foreignOwner","oldApplication","missing"})
    void pendingSessionCannotSurviveIdentityOrSourceDrift(String change) {
        switch(change) {
            case "unverified" -> user.setEmailVerified(false);
            case "erased" -> user.setErasedAt(java.time.LocalDateTime.now());
            case "inactive" -> user.setStatus(UserStatus.INACTIVE);
            case "studio" -> user.setStatus(UserStatus.PENDING_STUDIO_REQUEST);
            case "rejectedStudio" -> user.setStatus(UserStatus.REJECTED_STUDIO_REQUEST);
            case "listener" -> user.getRoles().add(Role.builder().id(UUID.randomUUID()).name("ROLE_LISTENER").build());
            case "venueRole" -> user.getRoles().add(Role.builder().id(UUID.randomUUID()).name("ROLE_VENUE").build());
            case "directPermission" -> user.getPermissions().add(Permission.builder().name("ADMIN_PANEL_ACCESS").build());
            case "foreignOwner" -> app.setApplicant(User.builder().id(UUID.randomUUID()).build());
            case "oldApplication" -> when(apps.findFirstByApplicant_IdOrderByApplicationDateDescCreatedAtDescIdDesc(user.getId()))
                    .thenReturn(Optional.of(VenueApplication.builder().id(UUID.randomUUID()).status(ApplicationStatus.PENDING).applicant(user).build()));
            case "missing" -> when(apps.findByIdAndApplicant_Id(app.getId(),user.getId())).thenReturn(Optional.empty());
        }
        assertThat(access.isAccessible(user,app.getId())).isFalse();
    }
    @Test void approvalNeedsExactDecisionVenueAndAllowsCurrentAdminAuthoritiesWithoutInventingThem() {
        user.setStatus(UserStatus.ACTIVE);app.setStatus(ApplicationStatus.APPROVED);
        user.getRoles().add(Role.builder().id(UUID.randomUUID()).name("ROLE_VENUE").build());user.getRoles().add(Role.builder().id(UUID.randomUUID()).name("ROLE_ADMIN").build());
        var venue=Venue.builder().id(UUID.randomUUID()).owner(user).status(VenueStatus.APPROVED).build();app.setApprovedVenue(venue);
        when(venues.findByIdAndOwnerId(venue.getId(),user.getId())).thenReturn(Optional.of(venue));
        assertThat(user.getRoles()).hasSize(2);
        assertThat(access.isAccessible(user,app.getId())).isTrue();
        assertThat(access.findSessionApplication(user)).isEmpty();
        when(venues.findByIdAndOwnerId(venue.getId(),user.getId())).thenReturn(Optional.empty());
        assertThat(access.isAccessible(user,app.getId())).isFalse();
        verify(venues,never()).findAllByOwnerId(any());
    }
    @Test void approvedSnapshotAloneCannotPromotePendingOrLegacyUnlinkedApplication() {
        app.setStatus(ApplicationStatus.APPROVED);
        assertThat(access.isAccessible(user,app.getId())).isFalse();assertThat(access.findSessionApplication(user)).isEmpty();
        user.setStatus(UserStatus.ACTIVE);user.getRoles().add(Role.builder().id(UUID.randomUUID()).name("ROLE_VENUE").build());
        assertThat(access.isAccessible(user,app.getId())).isFalse();
    }
}
