// src/test/java/com/berkayb/soundconnect/modules/media/repository/MediaAssetRepositoryTest.java
package com.berkayb.soundconnect.modules.media.repository;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@Import(JpaAuditingConfig.class)
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-media-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Tag("repo")
class MediaAssetRepositoryTest {
	
	@org.springframework.beans.factory.annotation.Autowired
	MediaAssetRepository repo;

	@Test
	void claimQueuedTranscode_isAtomicAndIdempotent() {
		MediaAsset queued = save(
				UUID.randomUUID(), MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.TRANSCODE_QUEUED);
		repo.flush();

		int first = claim(queued.getId());
		int duplicate = claim(queued.getId());

		assertThat(first).isEqualTo(1);
		assertThat(duplicate).isZero();
		assertThat(repo.findById(queued.getId()).orElseThrow().getStatus())
				.isEqualTo(MediaStatus.PROCESSING);
	}

	@Test
	void claimQueuedTranscode_neverClaimsProtectedVideo() {
		MediaAsset queued = save(
				UUID.randomUUID(), MediaKind.VIDEO, MediaVisibility.PRIVATE, MediaStatus.TRANSCODE_QUEUED);
		repo.flush();

		int claimed = claim(queued.getId());

		assertThat(claimed).isZero();
		assertThat(repo.findById(queued.getId()).orElseThrow().getStatus())
				.isEqualTo(MediaStatus.TRANSCODE_QUEUED);
	}

	@Test
	void finalizeHlsIfProcessing_isAtomicAndPublishesOnlyTheClaimedVideo() {
		MediaAsset processing = save(
				UUID.randomUUID(), MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.PROCESSING);
		repo.flush();
		String playback = "https://cdn.test/media/" + processing.getId() + "/hls/master.m3u8";
		String thumbnail = "https://cdn.test/media/" + processing.getId() + "/hls/thumbnail.jpg";

		int finalized = repo.finalizeHlsIfProcessing(
				processing.getId(),
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.PROCESSING,
				processing.getTranscodeAttemptToken(),
				LocalDateTime.now(),
				MediaStatus.READY,
				MediaStreamingProtocol.HLS,
				playback,
				thumbnail,
				123,
				1920,
				1080
		);

		assertThat(finalized).isEqualTo(1);
		MediaAsset ready = repo.findById(processing.getId()).orElseThrow();
		assertThat(ready.getStatus()).isEqualTo(MediaStatus.READY);
		assertThat(ready.getStreamingProtocol()).isEqualTo(MediaStreamingProtocol.HLS);
		assertThat(ready.getPlaybackUrl()).isEqualTo(playback);
		assertThat(ready.getThumbnailUrl()).isEqualTo(thumbnail);
	}

	@Test
	void finalizeHlsIfProcessing_neverResurrectsDeletionPendingOrMissingRows() {
		MediaAsset deleting = save(
				UUID.randomUUID(), MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.DELETION_PENDING);
		repo.flush();

		int deletingResult = repo.finalizeHlsIfProcessing(
				deleting.getId(),
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.PROCESSING,
				UUID.randomUUID(),
				LocalDateTime.now(),
				MediaStatus.READY,
				MediaStreamingProtocol.HLS,
				"https://cdn.test/master.m3u8",
				null,
				null,
				null,
				null
		);
		int missingResult = repo.finalizeHlsIfProcessing(
				UUID.randomUUID(),
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.PROCESSING,
				UUID.randomUUID(),
				LocalDateTime.now(),
				MediaStatus.READY,
				MediaStreamingProtocol.HLS,
				"https://cdn.test/master.m3u8",
				null,
				null,
				null,
				null
		);

		assertThat(deletingResult).isZero();
		assertThat(missingResult).isZero();
		assertThat(repo.findById(deleting.getId()).orElseThrow().getStatus())
				.isEqualTo(MediaStatus.DELETION_PENDING);
	}

	@Test
	void confirmedTranscode_isMarkedSentAndRemainsAtomicallyClaimable() {
		MediaAsset queued = save(
				UUID.randomUUID(), MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.TRANSCODE_QUEUED);
		repo.flush();

		assertThat(repo.markTranscodeSent(
				queued.getId(), MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.TRANSCODE_QUEUED, MediaStatus.TRANSCODE_SENT)).isEqualTo(1);
		assertThat(repo.markTranscodeSent(
				queued.getId(), MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.TRANSCODE_QUEUED, MediaStatus.TRANSCODE_SENT)).isZero();
		assertThat(claim(queued.getId())).isEqualTo(1);

		assertThat(repo.findById(queued.getId()).orElseThrow().getStatus())
				.isEqualTo(MediaStatus.PROCESSING);
	}

	@Test
	void staleSentTranscode_canBeConditionallyRequeued() {
		MediaAsset sent = save(
				UUID.randomUUID(), MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.TRANSCODE_SENT);
		repo.flush();
		LocalDateTime futureCutoff = LocalDateTime.now().plusMinutes(1);

		assertThat(repo.findByKindAndVisibilityAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.TRANSCODE_SENT,
				futureCutoff,
				PageRequest.of(0, 10)))
				.extracting(MediaAsset::getId)
				.contains(sent.getId());
		assertThat(repo.requeueStaleSentTranscode(
				sent.getId(),
				MediaKind.VIDEO,
				MediaVisibility.PUBLIC,
				MediaStatus.TRANSCODE_SENT,
				MediaStatus.TRANSCODE_QUEUED,
				futureCutoff)).isEqualTo(1);

		assertThat(repo.findById(sent.getId()).orElseThrow().getStatus())
				.isEqualTo(MediaStatus.TRANSCODE_QUEUED);
	}

	@Test
	void staleTranscodes_enterDurableHlsCleanupBeforeFailed() {
		MediaAsset processing = save(
				UUID.randomUUID(), MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.PROCESSING);
		MediaAsset queued = save(
				UUID.randomUUID(), MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.TRANSCODE_QUEUED);
		repo.flush();

		processing.setTranscodeLeaseUntil(LocalDateTime.now().minusMinutes(1));
		repo.flush();
		int failed = repo.recoverExpiredTranscodesForRetry(
				MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.PROCESSING, MediaStatus.HLS_CLEANUP,
				LocalDateTime.now(), 3);

		assertThat(failed).isEqualTo(1);
		assertThat(repo.findById(processing.getId()).orElseThrow().getStatus())
				.isEqualTo(MediaStatus.HLS_CLEANUP);
		assertThat(repo.findById(queued.getId()).orElseThrow().getStatus()).isEqualTo(MediaStatus.TRANSCODE_QUEUED);

		assertThat(repo.completeHlsCleanup(
				processing.getId(), MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.HLS_CLEANUP, MediaStatus.FAILED)).isEqualTo(1);
		assertThat(repo.findById(processing.getId()).orElseThrow().getStatus())
				.isEqualTo(MediaStatus.FAILED);
	}

	@Test
	void exhaustedInfrastructureFailure_keepsVerifiedSourceForManualRecovery() {
		MediaAsset cleanup = save(
				UUID.randomUUID(), MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.HLS_CLEANUP);
		String verifiedSource = "verified/media/" + cleanup.getId() + "/source.mp4";
		cleanup.setStorageKey(verifiedSource);
		cleanup.setSourceUrl("https://cdn.test/source.mp4");
		cleanup.setPlaybackUrl("https://cdn.test/partial/master.m3u8");
		cleanup.setThumbnailUrl("https://cdn.test/partial/thumbnail.jpg");
		cleanup.setTranscodeRetainSourceAfterCleanup(true);
		repo.saveAndFlush(cleanup);

		assertThat(repo.completeRetainedSourceFailure(
				cleanup.getId(), MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.HLS_CLEANUP, MediaStatus.FAILED)).isEqualTo(1);

		MediaAsset failed = repo.findById(cleanup.getId()).orElseThrow();
		assertThat(failed.getStatus()).isEqualTo(MediaStatus.FAILED);
		assertThat(failed.getStorageKey()).isEqualTo(verifiedSource);
		assertThat(failed.getSourceUrl()).isNull();
		assertThat(failed.getPlaybackUrl()).isNull();
		assertThat(failed.getThumbnailUrl()).isNull();
		assertThat(failed.isTranscodeRetainSourceAfterCleanup()).isFalse();
		assertThat(failed.getTranscodeCleanupNotBefore()).isNull();
	}

	@Test
	void staleUploadCleanupClaimAndFinishAreAtomicAndIdempotent() {
		MediaAsset uploading = save(
				UUID.randomUUID(), MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.UPLOADING);
		String storageKey = uploading.getStorageKey();
		repo.flush();

		int firstClaim = repo.claimStaleUploadForCleanup(
				uploading.getId(),
				MediaStatus.UPLOADING,
				MediaStatus.CLEANUP_PENDING,
				LocalDateTime.now().plusMinutes(1)
		);
		int duplicateClaim = repo.claimStaleUploadForCleanup(
				uploading.getId(),
				MediaStatus.UPLOADING,
				MediaStatus.CLEANUP_PENDING,
				LocalDateTime.now().plusMinutes(1)
		);

		assertThat(firstClaim).isEqualTo(1);
		assertThat(duplicateClaim).isZero();
		assertThat(repo.finishUploadCleanup(
				uploading.getId(), storageKey, MediaStatus.CLEANUP_PENDING, MediaStatus.FAILED
		)).isEqualTo(1);
		MediaAsset cleaned = repo.findById(uploading.getId()).orElseThrow();
		assertThat(cleaned.getStatus()).isEqualTo(MediaStatus.FAILED);
		assertThat(cleaned.getStorageKey()).isNull();
		assertThat(cleaned.getSourceUrl()).isNull();
	}
	
	private MediaAsset save(UUID ownerId, MediaKind kind, MediaVisibility vis, MediaStatus st) {
		var builder = MediaAsset.builder()
		                           .kind(kind)
		                           .visibility(vis)
		                           .status(st)
		                           .ownerType(MediaOwnerType.USER)
		                           .ownerId(ownerId)
		                           .mimeType(switch (kind) {
			                           case IMAGE -> "image/png";
			                           case AUDIO -> "audio/mpeg";
			                           case VIDEO -> "video/mp4";
		                           })
		                           .size(123L)
		                           .storageKey("media/" + UUID.randomUUID() + "/source")
		                           .sourceUrl("https://cdn.test/" + UUID.randomUUID());
		if (st == MediaStatus.PROCESSING) {
			builder.transcodeAttemptToken(UUID.randomUUID())
					.transcodeLeaseUntil(LocalDateTime.now().plusMinutes(15))
					.transcodeAttemptDeadline(LocalDateTime.now().plusHours(12))
					.transcodeAttemptCount(1);
		}
		return repo.save(builder.build());
	}

	private int claim(UUID assetId) {
		LocalDateTime now = LocalDateTime.now();
		return repo.claimQueuedTranscode(
				assetId, MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.TRANSCODE_QUEUED, MediaStatus.TRANSCODE_SENT,
				MediaStatus.PROCESSING, UUID.randomUUID(), now.plusMinutes(15),
				now.plusHours(12), 3);
	}

	@Test
	void protectedVisibility_neverPersistsStableDeliveryUrls() {
		MediaAsset asset = MediaAsset.builder()
				.kind(MediaKind.AUDIO)
				.visibility(MediaVisibility.UNLISTED)
				.status(MediaStatus.READY)
				.ownerType(MediaOwnerType.USER)
				.ownerId(UUID.randomUUID())
				.mimeType("audio/mpeg")
				.size(123L)
				.storageKey("protected/media/id/source.mp3")
				.sourceUrl("https://cdn.test/source.mp3")
				.playbackUrl("https://cdn.test/play.mp3")
				.thumbnailUrl("https://cdn.test/thumb.jpg")
				.build();

		MediaAsset persisted = repo.saveAndFlush(asset);

		assertThat(persisted.getSourceUrl()).isNull();
		assertThat(persisted.getPlaybackUrl()).isNull();
		assertThat(persisted.getThumbnailUrl()).isNull();
	}
	
	@Test
	void findByOwnerTypeAndOwnerId_should_returnOwnerAssets_paged() {
		UUID owner = UUID.randomUUID();
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner, MediaKind.VIDEO, MediaVisibility.PRIVATE, MediaStatus.UPLOADING);
		// başka owner
		save(UUID.randomUUID(), MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		
		Page<MediaAsset> page = repo.findByOwnerTypeAndOwnerId(MediaOwnerType.USER, owner, PageRequest.of(0, 10));
		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(page.getContent()).allMatch(a -> owner.equals(a.getOwnerId()));
	}
	
	@Test
	void findByOwnerTypeAndOwnerIdAndKind_should_filterByKind() {
		UUID owner = UUID.randomUUID();
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.READY);
		
		Page<MediaAsset> page = repo.findByOwnerTypeAndOwnerIdAndKind(
				MediaOwnerType.USER, owner, MediaKind.IMAGE, PageRequest.of(0, 10));
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent().get(0).getKind()).isEqualTo(MediaKind.IMAGE);
	}
	
	@Test
	void findByOwnerTypeAndOwnerIdAndVisibilityAndStatus_should_returnOnlyPublicReadyOfOwner() {
		UUID owner = UUID.randomUUID();
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner, MediaKind.IMAGE, MediaVisibility.PRIVATE, MediaStatus.READY);
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.PROCESSING);
		
		Page<MediaAsset> page = repo.findByOwnerTypeAndOwnerIdAndVisibilityAndStatus(
				MediaOwnerType.USER, owner, MediaVisibility.PUBLIC, MediaStatus.READY, PageRequest.of(0, 10));
		
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent().get(0).getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
		assertThat(page.getContent().get(0).getStatus()).isEqualTo(MediaStatus.READY);
	}
	
	@Test
	void findByOwnerTypeAndOwnerIdAndKindAndVisibilityAndStatus_should_filterAll() {
		UUID owner = UUID.randomUUID();
		save(owner, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.PROCESSING);
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		
		Page<MediaAsset> page = repo.findByOwnerTypeAndOwnerIdAndKindAndVisibilityAndStatus(
				MediaOwnerType.USER, owner, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.READY, PageRequest.of(0, 10));
		
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent().get(0).getKind()).isEqualTo(MediaKind.VIDEO);
		assertThat(page.getContent().get(0).getStatus()).isEqualTo(MediaStatus.READY);
	}
	
	@Test
	void findByVisibilityAndStatus_should_returnSystemWidePublicReady() {
		UUID owner1 = UUID.randomUUID();
		UUID owner2 = UUID.randomUUID();
		save(owner1, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner1, MediaKind.IMAGE, MediaVisibility.PRIVATE, MediaStatus.READY);
		save(owner2, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.READY);
		
		Page<MediaAsset> page = repo.findByVisibilityAndStatus(
				MediaVisibility.PUBLIC, MediaStatus.READY, PageRequest.of(0, 10));
		
		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(page.getContent()).allMatch(a ->
				                                       a.getVisibility() == MediaVisibility.PUBLIC && a.getStatus() == MediaStatus.READY);
	}
	
	@Test
	void findByVisibilityAndStatusAndKind_should_filterByKindSystemWide() {
		save(UUID.randomUUID(), MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(UUID.randomUUID(), MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.READY);
		
		Page<MediaAsset> page = repo.findByVisibilityAndStatusAndKind(
				MediaVisibility.PUBLIC, MediaStatus.READY, MediaKind.IMAGE, PageRequest.of(0, 10));
		
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent().get(0).getKind()).isEqualTo(MediaKind.IMAGE);
	}
	
	@Test
	void countByOwnerTypeAndOwnerId_should_returnCount() {
		UUID owner = UUID.randomUUID();
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner, MediaKind.AUDIO, MediaVisibility.PRIVATE, MediaStatus.UPLOADING);
		
		long count = repo.countByOwnerTypeAndOwnerId(MediaOwnerType.USER, owner);
		assertThat(count).isEqualTo(2);
	}
}
