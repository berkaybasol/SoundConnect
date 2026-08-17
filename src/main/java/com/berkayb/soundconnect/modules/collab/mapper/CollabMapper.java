package com.berkayb.soundconnect.modules.collab.mapper;

import com.berkayb.soundconnect.modules.collab.dto.response.*;
import com.berkayb.soundconnect.modules.collab.entity.*;
import com.berkayb.soundconnect.modules.collab.enums.*;
import com.berkayb.soundconnect.modules.collab.service.CollabActorService;
import com.berkayb.soundconnect.modules.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

@Component
@RequiredArgsConstructor
public class CollabMapper {
    private final CollabActorService actorService;

    public record ListingContext(UUID viewerId, Set<UUID> appliedIds, Set<UUID> savedIds,
                                 Map<UUID, Long> applicationCounts) {
        public static ListingContext empty(UUID viewerId) {
            return new ListingContext(viewerId, Set.of(), Set.of(), Map.of());
        }
    }

    public CollabListingResponse listing(Collab value, ListingContext context) {
        UUID viewerId = context.viewerId();
        User owner = value.getOwner();
        UUID ownerId = owner.getId();
        var instrument = value.getInstrument() == null ? null
                : new CollabInstrumentSummary(value.getInstrument().getId(), value.getInstrument().getName());
        return new CollabListingResponse(
                value.getId(), value.getVersion(), value.getStatus(), value.getClosureReason(), value.getCadence(),
                value.getWantedType(), instrument, value.getBranch(), value.getCustomSpecialty(), value.getTitle(),
                value.getDescription(), new CollabCitySummary(value.getCity().getId(), value.getCity().getName()),
                List.copyOf(value.getGenres()), value.getScheduledAt(), value.getExpiresAt(), value.getFeeAmountMinor(),
                value.getCurrency(), feeStatus(value), value.getPublishedAt(), value.getClosedAt(),
                toInstant(value.getCreatedAt()), actorService.toSummary(value.getPublisherActor(), owner),
                context.applicationCounts().getOrDefault(value.getId(), 0L),
                Objects.equals(viewerId, ownerId), context.appliedIds().contains(value.getId()),
                context.savedIds().contains(value.getId()));
    }

    public CollabApplicationResponse application(CollabApplication value, UUID viewerId, ListingContext listingContext) {
        boolean party = Objects.equals(viewerId, value.getApplicantUser().getId())
                || Objects.equals(viewerId, value.getListing().getOwner().getId());
        return new CollabApplicationResponse(value.getId(), value.getVersion(), value.getStatus(),
                listing(value.getListing(), listingContext),
                actorService.toSummary(value.getApplicantActor(), value.getApplicantUser()),
                party ? value.getPhoneSnapshot() : null, party ? value.getMessage() : null, value.getSubmittedAt(),
                value.getStatusChangedAt(), value.getDecidedAt());
    }

    public CollabJobResponse job(CollabJob value, UUID viewerId, boolean reviewedByMe,
                                 ListingContext listingContext) {
        boolean publisher = Objects.equals(viewerId, value.getPublisherUser().getId());
        return new CollabJobResponse(value.getId(), value.getVersion(), value.getStatus(),
                listing(value.getListing(), listingContext),
                actorService.toSummary(value.getPublisherActor(), value.getPublisherUser()),
                actorService.toSummary(value.getApplicantActor(), value.getApplicantUser()),
                value.getPublisherConfirmedAt() != null, value.getApplicantConfirmedAt() != null,
                value.getPublisherConfirmedAt(), value.getApplicantConfirmedAt(),
                publisher ? value.getPublisherConfirmedAt() != null : value.getApplicantConfirmedAt() != null,
                reviewedByMe, value.getCompletedAt());
    }

    public CollabReviewResponse review(CollabReview value) {
        User reviewer = value.getReviewerUser();
        User targetContactUser = Objects.equals(reviewer.getId(), value.getJob().getPublisherUser().getId())
                ? value.getJob().getApplicantUser()
                : value.getJob().getPublisherUser();
        return new CollabReviewResponse(value.getId(), value.getJob().getId(),
                actorService.toSummary(value.getReviewerActor(), reviewer),
                actorService.toSummary(value.getTargetActor(), targetContactUser), value.getRating(), value.getComment(),
                value.getSubmittedAt());
    }

    private CollabFeeStatus feeStatus(Collab value) {
        if (value.getFeeAmountMinor() != null) return CollabFeeStatus.SPECIFIED;
        if (value.getCadence() == CollabCadence.EXTRA
                || value.getPublisherActor().getProfileType() == com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType.VENUE) {
            return CollabFeeStatus.UNSPECIFIED;
        }
        return CollabFeeStatus.NOT_APPLICABLE;
    }

    private Instant toInstant(java.time.LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
