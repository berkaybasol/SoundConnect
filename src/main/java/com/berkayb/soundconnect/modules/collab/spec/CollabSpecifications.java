package com.berkayb.soundconnect.modules.collab.spec;

import com.berkayb.soundconnect.modules.collab.dto.request.CollabFilterRequest;
import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.entity.CollabActor;
import com.berkayb.soundconnect.modules.collab.enums.*;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.entity.MusicianProfile;
import com.berkayb.soundconnect.modules.profile.StudioProfile.entity.StudioProfile;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import jakarta.persistence.criteria.*;
import org.springframework.data.jpa.domain.Specification;

import java.time.*;
import java.util.*;

public final class CollabSpecifications {
    private CollabSpecifications() {}

    public static Specification<Collab> discovery(CollabFilterRequest filter, Instant now) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("status"), CollabListingStatus.OPEN));
            predicates.add(cb.or(cb.isNull(root.get("expiresAt")), cb.greaterThan(root.get("expiresAt"), now)));
            predicates.add(publisherOwnershipValidPredicate(root, query, cb));
            jakarta.persistence.criteria.Join<Object, Object> instrumentJoin = null;
            if (filter != null) {
                if (filter.cadence() != null) predicates.add(cb.equal(root.get("cadence"), filter.cadence()));
                if (filter.cityId() != null) predicates.add(cb.equal(root.get("city").get("id"), filter.cityId()));
                if (filter.wantedType() != null) predicates.add(cb.equal(root.get("wantedType"), filter.wantedType()));
                boolean instruments = hasValues(filter.instrumentIds());
                boolean branches = hasValues(filter.branches());
                if (instruments) instrumentJoin = root.join("instrument", jakarta.persistence.criteria.JoinType.LEFT);
                if (instruments && branches) {
                    predicates.add(cb.or(instrumentJoin.get("id").in(filter.instrumentIds()),
                            root.get("branch").in(filter.branches())));
                } else if (instruments) {
                    predicates.add(instrumentJoin.get("id").in(filter.instrumentIds()));
                } else if (branches) {
                    predicates.add(root.get("branch").in(filter.branches()));
                }
                if (hasValues(filter.publisherTypes())) predicates.add(root.get("publisherActor").get("profileType").in(filter.publisherTypes()));
                applyPublishedWindow(predicates, root, cb, filter.publishedWindow(), now);
                if (filter.q() != null && !filter.q().isBlank()) {
                    if (instrumentJoin == null) instrumentJoin = root.join("instrument", jakarta.persistence.criteria.JoinType.LEFT);
                    String pattern = "%" + escapeLike(filter.q().strip().toLowerCase(Locale.ROOT)) + "%";
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("title")), pattern, '\\'),
                            cb.like(cb.lower(root.get("description")), pattern, '\\'),
                            cb.like(cb.lower(root.get("customSpecialty")), pattern, '\\'),
                            cb.like(cb.lower(instrumentJoin.get("name")), pattern, '\\'),
                            cb.like(cb.lower(root.get("publisherActor").get("displayName")), pattern, '\\'),
                            cb.like(cb.lower(root.get("city").get("name")), pattern, '\\')));
                }
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private static void applyPublishedWindow(List<jakarta.persistence.criteria.Predicate> out,
                                             jakarta.persistence.criteria.Root<Collab> root,
                                             jakarta.persistence.criteria.CriteriaBuilder cb,
                                             CollabPublishedWindow window, Instant now) {
        if (window == null) return;
        Duration duration = switch (window) {
            case LAST_24_HOURS -> Duration.ofHours(24);
            case LAST_3_DAYS -> Duration.ofDays(3);
            case LAST_7_DAYS -> Duration.ofDays(7);
            case LAST_30_DAYS, OLDER_THAN_30_DAYS -> Duration.ofDays(30);
        };
        Instant threshold = now.minus(duration);
        out.add(window == CollabPublishedWindow.OLDER_THAN_30_DAYS
                ? cb.lessThan(root.get("publishedAt"), threshold)
                : cb.greaterThanOrEqualTo(root.get("publishedAt"), threshold));
    }

    private static boolean hasValues(Collection<?> values) { return values != null && !values.isEmpty(); }

    public static Specification<Collab> publisherOwnershipValid(UUID listingId) {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("id"), listingId),
                publisherOwnershipValidPredicate(root, query, cb));
    }

    public static Predicate publisherOwnershipValidPredicate(From<?, Collab> listing,
                                                              CriteriaQuery<?> query,
                                                              CriteriaBuilder cb) {
        Path<CollabActor> actor = listing.get("publisherActor");
        Path<ProfileType> profileType = actor.get("profileType");
        Path<UUID> sourceProfileId = actor.get("sourceProfileId");
        Path<UUID> ownerUserId = listing.<User>get("owner").get("id");

        Subquery<Integer> musicianOwnership = query.subquery(Integer.class);
        Root<MusicianProfile> musician = musicianOwnership.from(MusicianProfile.class);
        musicianOwnership.select(cb.literal(1)).where(
                cb.equal(musician.get("id"), sourceProfileId),
                cb.equal(musician.get("user").get("id"), ownerUserId));

        Subquery<Integer> bandOwnership = query.subquery(Integer.class);
        Root<BandMember> bandMember = bandOwnership.from(BandMember.class);
        bandOwnership.select(cb.literal(1)).where(
                cb.equal(bandMember.get("band").get("id"), sourceProfileId),
                cb.equal(bandMember.get("user").get("id"), ownerUserId),
                cb.equal(bandMember.get("status"), BandMemberShipStatus.ACTIVE),
                cb.equal(bandMember.get("bandRole"), BandRole.FOUNDER));

        Subquery<Integer> venueOwnership = query.subquery(Integer.class);
        Root<Venue> venue = venueOwnership.from(Venue.class);
        venueOwnership.select(cb.literal(1)).where(
                cb.equal(venue.get("id"), sourceProfileId),
                cb.equal(venue.get("owner").get("id"), ownerUserId));

        Subquery<Integer> studioOwnership = query.subquery(Integer.class);
        Root<StudioProfile> studio = studioOwnership.from(StudioProfile.class);
        studioOwnership.select(cb.literal(1)).where(
                cb.equal(studio.get("id"), sourceProfileId),
                cb.equal(studio.get("user").get("id"), ownerUserId));

        Predicate sourceIsStillOwned = cb.or(
                cb.and(cb.equal(profileType, ProfileType.MUSICIAN), cb.exists(musicianOwnership)),
                cb.and(cb.equal(profileType, ProfileType.BAND), cb.exists(bandOwnership)),
                cb.and(cb.equal(profileType, ProfileType.VENUE), cb.exists(venueOwnership)),
                cb.and(cb.equal(profileType, ProfileType.STUDIO), cb.exists(studioOwnership)));
        return cb.and(cb.isTrue(actor.get("active")), sourceIsStillOwned);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
