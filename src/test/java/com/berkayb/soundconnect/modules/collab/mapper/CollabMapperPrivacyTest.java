package com.berkayb.soundconnect.modules.collab.mapper;

import com.berkayb.soundconnect.modules.collab.dto.response.CollabActorSummary;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabApplicationResponse;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabJobResponse;
import com.berkayb.soundconnect.modules.collab.dto.response.CollabReviewResponse;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabActor;
import com.berkayb.soundconnect.modules.collab.entity.CollabApplication;
import com.berkayb.soundconnect.modules.collab.entity.CollabJob;
import com.berkayb.soundconnect.modules.collab.entity.CollabReview;
import com.berkayb.soundconnect.modules.collab.enums.CollabApplicationStatus;
import com.berkayb.soundconnect.modules.collab.enums.CollabCadence;
import com.berkayb.soundconnect.modules.collab.enums.CollabJobStatus;
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
        User owner = user("venue_owner");
        User applicant = user("bass_player");
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
        when(actorService.toSummary(any(CollabActor.class), any(User.class)))
                .thenAnswer(invocation -> summary(invocation.getArgument(0), invocation.getArgument(1)));
        CollabMapper mapper = new CollabMapper(actorService);

        CollabApplicationResponse stranger = mapper.application(
                application, UUID.randomUUID(), CollabMapper.ListingContext.empty(UUID.randomUUID()));
        CollabApplicationResponse ownerView = mapper.application(
                application, owner.getId(), CollabMapper.ListingContext.empty(owner.getId()));

        assertThat(stranger.phoneNumber()).isNull();
        assertThat(stranger.message()).isNull();
        assertThat(stranger.listing().publisher().contactUserId()).isEqualTo(owner.getId());
        assertThat(stranger.listing().publisher().contactUsername()).isEqualTo("venue_owner");
        assertThat(stranger.applicant().contactUserId()).isEqualTo(applicant.getId());
        assertThat(stranger.applicant().contactUsername()).isEqualTo("bass_player");
        assertThat(ownerView.phoneNumber()).isEqualTo("+905321112233");
        assertThat(ownerView.message()).isEqualTo("Akşam programı için uygunum.");
    }

    @Test
    void jobAndReviewUseTheUserThatOwnsEachActorSummary() {
        User owner = user("venue_owner");
        User applicant = user("bass_player");
        CollabActor publisherActor = actor(ProfileType.VENUE);
        CollabActor applicantActor = actor(ProfileType.MUSICIAN);
        Collab listing = listing(owner, publisherActor);
        CollabApplication application = CollabApplication.builder()
                .listing(listing)
                .applicantActor(applicantActor)
                .applicantUser(applicant)
                .status(CollabApplicationStatus.ACCEPTED)
                .submittedAt(Instant.now())
                .statusChangedAt(Instant.now())
                .build();
        application.setId(UUID.randomUUID());
        CollabJob job = CollabJob.builder()
                .listing(listing)
                .application(application)
                .publisherActor(publisherActor)
                .applicantActor(applicantActor)
                .publisherUser(owner)
                .applicantUser(applicant)
                .status(CollabJobStatus.ACTIVE)
                .build();
        job.setId(UUID.randomUUID());
        CollabReview review = CollabReview.builder()
                .job(job)
                .reviewerActor(applicantActor)
                .targetActor(publisherActor)
                .reviewerUser(applicant)
                .rating(5)
                .comment("Harika bir sahneydi.")
                .submittedAt(Instant.now())
                .build();
        review.setId(UUID.randomUUID());
        when(actorService.toSummary(any(CollabActor.class), any(User.class)))
                .thenAnswer(invocation -> summary(invocation.getArgument(0), invocation.getArgument(1)));
        CollabMapper mapper = new CollabMapper(actorService);

        CollabJobResponse jobResponse = mapper.job(
                job, applicant.getId(), false, CollabMapper.ListingContext.empty(applicant.getId()));
        CollabReviewResponse reviewResponse = mapper.review(review);

        assertThat(jobResponse.publisher().contactUsername()).isEqualTo("venue_owner");
        assertThat(jobResponse.applicant().contactUsername()).isEqualTo("bass_player");
        assertThat(reviewResponse.reviewer().contactUserId()).isEqualTo(applicant.getId());
        assertThat(reviewResponse.reviewer().contactUsername()).isEqualTo("bass_player");
        assertThat(reviewResponse.target().contactUserId()).isEqualTo(owner.getId());
        assertThat(reviewResponse.target().contactUsername()).isEqualTo("venue_owner");
    }

    private User user(String username) {
        User value = new User();
        value.setId(UUID.randomUUID());
        value.setUsername(username);
        return value;
    }

    private Collab listing(User owner, CollabActor publisherActor) {
        City city = new City();
        city.setId(UUID.randomUUID());
        city.setName("İstanbul");
        Collab value = Collab.builder()
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

    private CollabActorSummary summary(CollabActor actor, User contactUser) {
        return new CollabActorSummary(actor.getId(), actor.getProfileType(), actor.getSourceProfileId(),
                contactUser.getId(), contactUser.getUsername(), actor.getDisplayName(), null,
                java.math.BigDecimal.ZERO, 0, 0);
    }
}
