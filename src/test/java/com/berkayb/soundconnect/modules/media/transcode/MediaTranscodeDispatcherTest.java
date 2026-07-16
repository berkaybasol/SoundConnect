package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.transcode.config.MediaTranscodeLeaseProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.core.task.TaskRejectedException;

import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class MediaTranscodeDispatcherTest {
	@Mock MediaAssetRepository mediaAssetRepository;
	@Mock TranscodePublisher transcodePublisher;
	@Mock MediaPolicy mediaPolicy;
	@Mock MediaAssetStatusUpdater mediaAssetStatusUpdater;
	@Mock MediaHlsCleanupDispatcher hlsCleanupDispatcher;
	private MediaTranscodeLeaseProperties leaseProperties;

	private MediaTranscodeDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		leaseProperties = new MediaTranscodeLeaseProperties();
		dispatcher = new MediaTranscodeDispatcher(
				mediaAssetRepository, transcodePublisher, mediaPolicy,
				mediaAssetStatusUpdater, hlsCleanupDispatcher, leaseProperties, Runnable::run);
		ReflectionTestUtils.setField(dispatcher, "batchSize", 50);
		ReflectionTestUtils.setField(dispatcher, "sentRecoveryDelay", Duration.ofMinutes(5));
		ReflectionTestUtils.setField(dispatcher, "hlsCleanupQuietPeriodHours", 1L);
	}

	@Test
	void afterCommitEvent_dispatchesOnlyDurablyQueuedAsset() {
		MediaAsset asset = queuedVideo();
		when(mediaAssetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
		when(mediaPolicy.buildHlsPrefix(asset.getId())).thenReturn("media/" + asset.getId() + "/hls");
		when(mediaAssetStatusUpdater.markTranscodeSent(asset.getId())).thenReturn(true);

		dispatcher.onQueued(new MediaTranscodeQueuedEvent(asset.getId()));

		InOrder order = inOrder(mediaAssetStatusUpdater, transcodePublisher);
		order.verify(mediaAssetStatusUpdater).markTranscodeSent(asset.getId());
		order.verify(transcodePublisher).publishVideoHls(
				asset.getId(), asset.getStorageKey(), "media/" + asset.getId() + "/hls");
	}

	@Test
	void afterCommitEvent_neverDispatchesProtectedVideo() {
		MediaAsset asset = queuedVideo();
		asset.setVisibility(MediaVisibility.PRIVATE);
		when(mediaAssetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));

		dispatcher.onQueued(new MediaTranscodeQueuedEvent(asset.getId()));

		verifyNoInteractions(transcodePublisher, mediaPolicy);
	}

	@Test
	void recovery_keepsQueuedIntentWhenBrokerPublishFails() {
		MediaAsset asset = queuedVideo();
		when(mediaAssetRepository.findByKindAndVisibilityAndStatusOrderByCreatedAtAsc(
				eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC), eq(MediaStatus.TRANSCODE_QUEUED), any()))
				.thenReturn(new PageImpl<>(List.of(asset)));
		when(mediaAssetRepository.findById(asset.getId())).thenReturn(Optional.of(asset));
		when(mediaPolicy.buildHlsPrefix(asset.getId())).thenReturn("media/" + asset.getId() + "/hls");
		when(mediaAssetStatusUpdater.markTranscodeSent(asset.getId())).thenReturn(true);
		when(mediaAssetStatusUpdater.requeueUnclaimedTranscodeSignal(asset.getId())).thenReturn(true);
		doThrow(new IllegalStateException("broker unavailable"))
				.when(transcodePublisher)
				.publishVideoHls(asset.getId(), asset.getStorageKey(), "media/" + asset.getId() + "/hls");

		assertThatCode(dispatcher::recoverQueuedJobs).doesNotThrowAnyException();
		verify(mediaAssetRepository).findById(asset.getId());
		verify(mediaAssetStatusUpdater).markTranscodeSent(asset.getId());
		verify(mediaAssetStatusUpdater).requeueUnclaimedTranscodeSignal(asset.getId());
	}

	@Test
	void staleSentMonitor_requeuesOnlyRowsStillStaleAndSent() {
		MediaAsset asset = queuedVideo();
		asset.setStatus(MediaStatus.TRANSCODE_SENT);
		when(mediaAssetRepository.findByKindAndVisibilityAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
				eq(MediaKind.VIDEO),
				eq(MediaVisibility.PUBLIC),
				eq(MediaStatus.TRANSCODE_SENT),
				any(LocalDateTime.class),
				any()))
				.thenReturn(List.of(asset));
		when(mediaAssetRepository.requeueStaleSentTranscode(
				eq(asset.getId()),
				eq(MediaKind.VIDEO),
				eq(MediaVisibility.PUBLIC),
				eq(MediaStatus.TRANSCODE_SENT),
				eq(MediaStatus.TRANSCODE_QUEUED),
				any(LocalDateTime.class)))
				.thenReturn(1);

		dispatcher.recoverStaleSentJobs();

		verify(mediaAssetRepository).requeueStaleSentTranscode(
				eq(asset.getId()),
				eq(MediaKind.VIDEO),
				eq(MediaVisibility.PUBLIC),
				eq(MediaStatus.TRANSCODE_SENT),
				eq(MediaStatus.TRANSCODE_QUEUED),
				any(LocalDateTime.class));
	}

	@Test
	void expiredLeaseMonitorSeparatesRetryableAndExhaustedAttempts() {
		when(mediaAssetRepository.recoverExpiredTranscodesForRetry(
				eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC), eq(MediaStatus.PROCESSING),
				eq(MediaStatus.HLS_CLEANUP), any(), eq(3))).thenReturn(1);
		when(mediaAssetRepository.recoverExhaustedTranscodesForCleanup(
				eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC), eq(MediaStatus.PROCESSING),
				eq(MediaStatus.HLS_CLEANUP), any(), eq(3))).thenReturn(1);

		dispatcher.recoverExpiredProcessingJobs();

		verify(mediaAssetRepository).recoverExpiredTranscodesForRetry(
				eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC), eq(MediaStatus.PROCESSING),
				eq(MediaStatus.HLS_CLEANUP), any(LocalDateTime.class), eq(3));
		verify(mediaAssetRepository).recoverExhaustedTranscodesForCleanup(
				eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC), eq(MediaStatus.PROCESSING),
				eq(MediaStatus.HLS_CLEANUP), any(LocalDateTime.class), eq(3));
	}

	@Test
	void hlsCleanupRecovery_submitsOnlyQuietDurableIntents() {
		MediaAsset asset = queuedVideo();
		asset.setStatus(MediaStatus.HLS_CLEANUP);
		when(mediaAssetRepository.findByKindAndVisibilityAndStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
				eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC), eq(MediaStatus.HLS_CLEANUP),
				any(LocalDateTime.class), any()))
				.thenReturn(List.of(asset));
		when(hlsCleanupDispatcher.submit(asset.getId())).thenReturn(true);

		dispatcher.recoverHlsCleanupJobs();

		verify(hlsCleanupDispatcher).submit(asset.getId());
	}

	@Test
	void saturatedDispatchExecutorRetainsQueuedIntentWithoutPublishing() {
		MediaTranscodeDispatcher saturated = new MediaTranscodeDispatcher(
				mediaAssetRepository, transcodePublisher, mediaPolicy,
				mediaAssetStatusUpdater, hlsCleanupDispatcher, leaseProperties,
				task -> { throw new TaskRejectedException("queue full"); });

		assertThatCode(() -> saturated.onQueued(
				new MediaTranscodeQueuedEvent(UUID.randomUUID())))
				.doesNotThrowAnyException();

		verifyNoInteractions(transcodePublisher, mediaAssetStatusUpdater);
	}

	private static MediaAsset queuedVideo() {
		return MediaAsset.builder()
				.id(UUID.randomUUID())
				.kind(MediaKind.VIDEO)
				.status(MediaStatus.TRANSCODE_QUEUED)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(UUID.randomUUID())
				.mimeType("video/mp4")
				.size(100L)
				.storageKey("verified/media/video/source.mp4")
				.build();
	}
}
