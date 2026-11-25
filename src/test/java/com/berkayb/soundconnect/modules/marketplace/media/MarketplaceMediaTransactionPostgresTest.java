package com.berkayb.soundconnect.modules.marketplace.media;

import com.berkayb.soundconnect.modules.marketplace.MarketplaceTypes.*;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceRequests.*;
import com.berkayb.soundconnect.modules.marketplace.dto.MarketplaceResponses.Listing;
import com.berkayb.soundconnect.modules.media.abuse.MediaUploadAbuseGuard;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetReferenceGuard;
import com.berkayb.soundconnect.modules.media.support.MediaJpaPostgresFixture;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises production Spring proxies with one JpaTransactionManager shared by JDBC and JPA. */
class MarketplaceMediaTransactionPostgresTest extends MediaJpaPostgresFixture {
    @Test
    void replacementCommitsOrderJpaDeletionFenceAndAfterCommitEventTogether() {
        var listing = draft();
        var removed = image(listing.id()); var kept = image(listing.id()); var added = image(listing.id());
        listing = update(listing, removed.getId(), kept.getId());

        var updated = update(listing, added.getId(), kept.getId());

        assertThat(photos(listing.id())).containsExactly(added.getId(), kept.getId());
        assertThat(updated.version()).isEqualTo(listing.version() + 1);
        var deleted = asset(removed.getId());
        assertThat(deleted.getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(deleted.getPhysicalDeletionNotBefore()).isAfter(deleted.getDeletionRequestedAt());
        assertThat(asset(kept.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(asset(added.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).containsExactly(removed.getId());
        verify(context.getBean(MediaUploadAbuseGuard.class)).release(removed.getId());
    }

    @Test
    void deferredDatabaseCommitFailureRollsBackPhotoOrderVersionAndJpaDeleteIntent() {
        var draft = draft();
        var before = image(draft.id()); var replacement = image(draft.id());
        var listing = update(draft, before.getId());
        events.failAtCommit = true;

        assertThatThrownBy(() -> update(listing, replacement.getId())).isInstanceOf(RuntimeException.class);

        assertThat(photos(listing.id())).containsExactly(before.getId());
        assertThat(service.detail(seller, listing.id()).version()).isEqualTo(listing.version());
        assertThat(asset(before.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(asset(before.getId()).getDeletionRequestedAt()).isNull();
        assertThat(asset(replacement.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).isEmpty();
        verify(context.getBean(MediaUploadAbuseGuard.class), never()).release(any());
        assertThat(jdbc.queryForObject("select count(*) from test_commit_guard", Map.of(), Long.class)).isZero();
    }

    @Test
    void draftDeletionRemovesJdbcRowsAndCommitsOneDurableIntentForEveryOwnedAsset() {
        var listing = draft();
        var attached = image(listing.id()); var uncommittedUpload = image(listing.id());
        listing = update(listing, attached.getId());

        service.deleteDraft(seller, listing.id(), listing.version());

        assertThat(photos(listing.id())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_listing where id=:id", Map.of("id", listing.id()), Long.class)).isZero();
        assertThat(asset(attached.getId()).getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(asset(uncommittedUpload.getId()).getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(events.committed).containsExactlyInAnyOrder(attached.getId(), uncommittedUpload.getId());
    }

    @Test
    void draftDeletionExceptionRestoresBothJdbcReferencesAndManagedEntityState() {
        var draft = draft(); var photo = image(draft.id()); var listing = update(draft, photo.getId());
        events.failImmediately = true;

        assertThatThrownBy(() -> service.deleteDraft(seller, listing.id(), listing.version()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(photos(listing.id())).containsExactly(photo.getId());
        assertThat(service.detail(seller, listing.id()).version()).isEqualTo(listing.version());
        assertThat(asset(photo.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).isEmpty();
        verify(context.getBean(MediaUploadAbuseGuard.class), never()).release(any());
    }

    @Test
    void lostUpdateResponseCannotCauseClientCleanupToDeleteTheCommittedReplacement() {
        var listing = draft(); var original = image(listing.id()); var replacement = image(listing.id());
        listing = update(listing, original.getId());
        update(listing, replacement.getId()); // Commit succeeds; the client never receives this response.
        UUID id = listing.id();

        assertThatThrownBy(() -> lifecycle.deleteDetachedAssets(seller, id, List.of(replacement.getId())))
                .isInstanceOf(SoundConnectException.class);

        assertThat(photos(id)).containsExactly(replacement.getId());
        assertThat(asset(replacement.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).containsExactly(original.getId());
    }

    @Test
    void accountPurgeRemovesPublishedListingAndPreservesReportedEvidenceAndOtherSeller() {
        UUID viewer = account("viewer");
        UUID moderator = account("moderator");
        jdbc.update("insert into user_permissions select :user,id from tbl_permissions where name='MANAGE_MARKETPLACE_REPORTS'",
                Map.of("user", moderator));
        UUID city = UUID.randomUUID(), district = UUID.randomUUID();
        jdbc.update("insert into tbl_city values(:id,'Ankara')", Map.of("id", city));
        jdbc.update("insert into tbl_district values(:id,:city,'Çankaya')", Map.of("id", district, "city", city));
        UUID category = service.categories(seller).getFirst().children().getFirst().id();
        var listing = publish(seller, "Reported guitar", category, district);
        var other = publish(buyer, "Other guitar", category, district);
        UUID evidence = listing.photos().getFirst().assetId(), otherPhoto = other.photos().getFirst().assetId();
        var report = service.report(viewer, listing.id(), new Report(ReportReason.SCAM, "Wrong description", UUID.randomUUID()));
        var snapshot = service.reports(moderator, ReportStatus.OPEN, 0, 20).content().getFirst().evidence();
        assertThat(snapshot.path("title").asText()).isEqualTo(listing.title());
        assertThat(snapshot.path("photos").get(0).path("assetId").asText()).isEqualTo(evidence.toString());

        UUID replacement = image(listing.id()).getId();
        service.update(seller, listing.id(), listedItem(listing, "Edited guitar", category, district, replacement));
        service.save(viewer, listing.id());
        service.save(viewer, other.id());
        service.save(seller, other.id());
        assertThat(service.discovery(viewer, null, 0, 20).content()).extracting(Listing::id)
                .containsExactlyInAnyOrder(listing.id(), other.id());
        var preserved = service.detail(viewer, other.id());
        for (int attempt = 0; attempt < 2; attempt++) {
            transaction.executeWithoutResult(status -> { listings.lockUser(seller); lifecycle.purgeAccount(seller); });
        }

        assertThat(service.discovery(viewer, null, 0, 20).content()).containsExactly(preserved);
        assertThat(service.saved(viewer, 0, 20).content()).containsExactly(preserved);
        assertThat(service.saved(seller, 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> service.detail(viewer, listing.id())).isInstanceOfSatisfying(SoundConnectException.class,
                ex -> assertThat(ex.getErrorType()).isEqualTo(ErrorType.MARKETPLACE_NOT_FOUND));
        assertThat(service.detail(viewer, other.id())).isEqualTo(preserved);
        assertThat(asset(otherPhoto).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(photos(listing.id())).isEmpty();
        assertThat(asset(replacement).getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(events.committed).containsExactly(replacement);

        var retained = service.reports(moderator, ReportStatus.OPEN, 0, 20).content().getFirst();
        assertThat(retained.id()).isEqualTo(report.id());
        assertThat(retained.listingId()).isNull();
        assertThat(retained.evidence()).isEqualTo(snapshot);
        assertThat(asset(evidence).getStatus()).isEqualTo(MediaStatus.READY);
        var mediaAccess = context.getBean(MarketplaceMediaAccess.class);
        mediaAccess.requireMediaAccess(moderator, listing.id(), evidence);
        assertThatThrownBy(() -> mediaAccess.requireMediaAccess(viewer, listing.id(), evidence))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        ex -> assertThat(ex.getErrorType()).isEqualTo(ErrorType.MEDIA_ASSET_NOT_FOUND));
        assertThatThrownBy(() -> context.getBean(MediaAssetReferenceGuard.class).assertNotReferenced(evidence))
                .isInstanceOfSatisfying(SoundConnectException.class,
                        ex -> assertThat(ex.getErrorType()).isEqualTo(ErrorType.MEDIA_ASSET_IN_USE));
    }

    private Listing publish(UUID owner, String title, UUID category, UUID district) {
        var draft = service.createDraft(owner, new Draft(UUID.randomUUID()));
        var complete = service.update(owner, draft.id(), listedItem(draft, title, category, district, image(draft.id()).getId()));
        return service.publish(owner, draft.id(), new Version(complete.version()));
    }

    private Update listedItem(Listing listing, String title, UUID category, UUID district, UUID photo) {
        return new Update(listing.version(), title, "Used electric guitar", category, null, null, Condition.USED,
                50000L, district, List.of(photo), false, Delivery.PICKUP);
    }

    @Test
    void twoSellersWhoSavedEachOthersListingsCanPurgeConcurrently() throws Exception {
        UUID city = UUID.randomUUID(), district = UUID.randomUUID();
        jdbc.update("insert into tbl_city values(:id,'Ankara')", Map.of("id", city));
        jdbc.update("insert into tbl_district values(:id,:city,'Çankaya')", Map.of("id", district, "city", city));
        UUID category = service.categories(seller).getFirst().children().getFirst().id();
        Listing first = publish(seller, "First guitar", category, district);
        Listing second = publish(buyer, "Second guitar", category, district);
        service.save(buyer, first.id());
        service.save(seller, second.id());

        var locked = new CountDownLatch(2);
        var start = new CompletableFuture<Void>().orTimeout(10, TimeUnit.SECONDS);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var purges = List.of(seller, buyer).stream().map(owner -> pool.submit(() ->
                    transaction.executeWithoutResult(status -> {
                        jdbc.getJdbcTemplate().execute("set local lock_timeout='5s'");
                        listings.lockUser(owner);
                        jdbc.queryForList("select id from tbl_marketplace_listing where owner_user_id=:user for update",
                                Map.of("user", owner), UUID.class);
                        locked.countDown();
                        start.join();
                        lifecycle.purgeAccount(owner);
                    }))).toList();
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.complete(null);
            }
            for (var purge : purges) purge.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_saved", Map.of(), Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from tbl_marketplace_listing", Map.of(), Long.class)).isZero();
        assertThat(events.committed).containsExactlyInAnyOrder(
                first.photos().getFirst().assetId(), second.photos().getFirst().assetId());
    }

    @Test
    void accountPurgeCommitFailureRestoresAllListingsPhotoReferencesAndMediaStates() {
        var first = draft(); var a = image(first.id()); first = update(first, a.getId());
        var second = draft(); var b = image(second.id()); second = update(second, b.getId());
        events.failAtCommit = true;

        assertThatThrownBy(() -> tx(() -> { listings.lockUser(seller); lifecycle.purgeAccount(seller); return null; }))
                .isInstanceOf(RuntimeException.class);

        assertThat(photos(first.id())).containsExactly(a.getId());
        assertThat(photos(second.id())).containsExactly(b.getId());
        assertThat(asset(a.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(asset(b.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).isEmpty();
        verify(context.getBean(MediaUploadAbuseGuard.class), never()).release(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/Istanbul", "America/New_York"})
    void cleanupHonorsSame24HourAgeAsJpaRegardlessOfJvmZone(String zone) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        var listing = draft();
        var young = image(listing.id()); var old = image(listing.id());
        var attached = image(listing.id()); var evidence = image(listing.id());
        update(listing, attached.getId()); retainInReport(listing.id(), evidence.getId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).withNano(0);
        LocalDateTime oldAudit = now.minusHours(24).minusSeconds(30);
        age(young.getId(), now.minusHours(24).plusSeconds(30)); age(old.getId(), oldAudit);
        age(attached.getId(), now.minusDays(3)); age(evidence.getId(), now.minusDays(3));

        // Explicitly reproduce the raw-column/JPA distinction under the production binding.
        LocalDateTime raw = jdbc.queryForObject("select created_at from tbl_media_asset where id=:id",
                Map.of("id", old.getId()), (row, n) -> row.getObject(1, LocalDateTime.class));
        int offset = TimeZone.getDefault().toZoneId().getRules().getOffset(oldAudit).getTotalSeconds();
        assertThat(raw).isEqualTo(oldAudit.minusSeconds(offset));
        assertThat(asset(old.getId()).getCreatedAt()).isEqualTo(oldAudit);

        assertThat(assets.findMarketplaceOrphansBefore(now.minusHours(24), PageRequest.of(0, 100)))
                .extracting(MediaAssetRepository.MarketplaceOrphan::getAssetId).containsExactly(old.getId());
        new MarketplaceMediaCleanupScheduler(assets, lifecycle).cleanup();

        assertThat(asset(old.getId()).getStatus()).as("24h + 30s must be eligible in %s", zone)
                .isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(asset(young.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(asset(attached.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(asset(evidence.getId()).getStatus()).isEqualTo(MediaStatus.READY);
        assertThat(events.committed).containsExactly(old.getId());
    }
}
