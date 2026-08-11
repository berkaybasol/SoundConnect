package com.berkayb.soundconnect.modules.collab.mapper;

import com.berkayb.soundconnect.modules.collab.dto.response.CollabActorSummary;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabApplicationResponse;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabActor;
import com.berkayb.soundconnect.modules.collab.entity.CollabApplication;
import com.berkayb.soundconnect.modules.collab.enums.CollabApplicationStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabCadence;
import com.berkayb.soundconnect.modules.collab.enums.CollabListingStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;
import com.berkayb.soundconnect.modules.collab.service.CollabActorService;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CollabMapperPrivacyTest {
    @Mock private CollabActorService actorService;

    @Test
    void applicationContactSnapshotIsVisibleOnlyToTheTwoParties() {
        User owner = user();
        User applicant = user();
        CollabActor publisherActor = actor(ProfileType.VENUE);
        CollabActor applicantActor = actor(ProfileType.MUSICIAN);
        City city = new City();
        city.setId(UUID.randomUUID());
        city.setName("İstanbul");
        Collab listing = Collab.builder()
                .owner(owner)
                .publisherActor(publisherActor)
                .cadence(CollabCadence.REGULAR)
                .wantedType(CollabWantedType.MUSICIAN)
                .title("Bas gitarist arıyoruz")
                .description("Düzenli sahne programımız için bas gitarist arıyoruz.")
                .city(city)
                .genres(new ArrayList<>())
                .status(CollabListingStatus.OPEN)
                .build();
        listing.setId(UUID.randomUUID());
        CollabApplication application = CollabApplication.builder()
                .listing(listing)
                .applicantActor(applicantActor)
                .applicantUser(applicant)
                .phoneSnapshot("+905321112233")
                .message("Akşam programı için uygunum.")
                .status(CollabApplicationStatus.PENDING)
                .submittedAt(Instant.now())
                .statusChangedAt(Instant.now())
                .build();
        application.setId(UUID.randomUUID());
        when(actorService.toSummary(any(CollabActor.class), any(UUID.class)))
                .thenAnswer(invocation -> summary(invocation.getArgument(0), invocation.getArgument(1)));
        CollabMapper mapper = new CollabMapper(actorService);

        CollabApplicationResponse stranger = mapper.application(
                application, UUID.randomUUID(), CollabMapper.ListingContext.empty(UUID.randomUUID()));
        CollabApplicationResponse ownerView = mapper.application(
                application, owner.getId(), CollabMapper.ListingContext.empty(owner.getId()));

        assertThat(stranger.phoneNumber()).isNull();
        assertThat(stranger.message()).isNull();
        assertThat(ownerView.phoneNumber()).isEqualTo("+905321112233");
        assertThat(ownerView.message()).isEqualTo("Akşam programı için uygunum.");
    }

    private User user() {
        User value = new User();
        value.setId(UUID.randomUUID());
        return value;
    }

    private CollabActor actor(ProfileType type) {
        CollabActor value = CollabActor.builder()
                .profileType(type)
                .sourceProfileId(UUID.randomUUID())
                .displayName(type.name())
                .build();
        value.setId(UUID.randomUUID());
        return value;
    }

    private CollabActorSummary summary(CollabActor actor, UUID contactUserId) {
        return new CollabActorSummary(actor.getId(), actor.getProfileType(), actor.getSourceProfileId(),
                contactUserId, actor.getDisplayName(), null, java.math.BigDecimal.ZERO, 0, 0);
    }
}
