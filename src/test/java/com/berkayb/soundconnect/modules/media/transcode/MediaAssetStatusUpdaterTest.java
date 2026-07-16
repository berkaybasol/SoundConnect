package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.transcode.config.MediaTranscodeLeaseProperties;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class MediaAssetStatusUpdaterTest {

	@Mock MediaAssetRepository mediaRepo;
	MediaTranscodeLeaseProperties leaseProperties;
	MediaAssetStatusUpdater updater;

	@BeforeEach
	void setup() {
		leaseProperties = new MediaTranscodeLeaseProperties();
		leaseProperties.setDuration(Duration.ofMinutes(15));
		leaseProperties.setHeartbeatInterval(Duration.ofMinutes(1));
		leaseProperties.setMaxAttempts(3);
		updater = new MediaAssetStatusUpdater(mediaRepo, leaseProperties, 12);
	}

	@Test
	void claim_setsUniqueTokenLeaseDeadlineAndMonotonicAttempt() {
		UUID id = UUID.randomUUID();
		MediaAsset asset = video(MediaStatus.TRANSCODE_QUEUED, id);
		when(mediaRepo.claimQueuedTranscode(
				eq(id), eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC),
				eq(MediaStatus.TRANSCODE_QUEUED), eq(MediaStatus.TRANSCODE_SENT),
				eq(MediaStatus.PROCESSING), any(UUID.class), any(LocalDateTime.class),
				any(LocalDateTime.class), eq(3)))
				.thenAnswer(invocation -> {
					asset.setStatus(MediaStatus.PROCESSING);
					asset.setTranscodeAttemptToken(invocation.getArgument(6));
					asset.setTranscodeLeaseUntil(invocation.getArgument(7));
					asset.setTranscodeAttemptDeadline(invocation.getArgument(8));
					asset.setTranscodeAttemptCount(1);
					return 1;
				});
		when(mediaRepo.findById(id)).thenReturn(Optional.of(asset));

		var claim = updater.tryClaimQueuedTranscode(id).orElseThrow();

		assertThat(claim.attemptToken()).isEqualTo(asset.getTranscodeAttemptToken());
		assertThat(claim.attemptNumber()).isEqualTo(1);
		assertThat(claim.leaseUntil()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(14));
		assertThat(claim.attemptDeadline()).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusHours(11));
	}

	@Test
	void duplicateOrExhaustedClaim_isIgnoredWithoutReadingSource() {
		UUID id = UUID.randomUUID();
		when(mediaRepo.claimQueuedTranscode(
				eq(id), any(), any(), any(), any(), any(), any(), any(), any(), eq(3)))
				.thenReturn(0);

		assertThat(updater.tryClaimQueuedTranscode(id)).isEmpty();
		verify(mediaRepo, never()).findById(id);
	}

	@Test
	void claimRejectsSourceOutsideVerifiedNamespaceAndRollsBackTransaction() {
		UUID id = UUID.randomUUID();
		MediaAsset asset = video(MediaStatus.PROCESSING, id);
		asset.setStorageKey("quarantine/media/" + id + "/source.mp4");
		asset.setTranscodeAttemptToken(UUID.randomUUID());
		asset.setTranscodeLeaseUntil(LocalDateTime.now().plusMinutes(15));
		asset.setTranscodeAttemptDeadline(LocalDateTime.now().plusHours(12));
		when(mediaRepo.claimQueuedTranscode(
				eq(id), any(), any(), any(), any(), any(), any(), any(), any(), eq(3)))
				.thenReturn(1);
		when(mediaRepo.findById(id)).thenReturn(Optional.of(asset));

		assertThatThrownBy(() -> updater.tryClaimQueuedTranscode(id))
				.isInstanceOf(SoundConnectException.class);
	}

	@Test
	void heartbeatRenewal_isFencedByExactUnexpiredToken() {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		when(mediaRepo.renewTranscodeLease(
				eq(id), eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC),
				eq(MediaStatus.PROCESSING), eq(token), any(), any()))
				.thenReturn(1, 0);

		assertThat(updater.renewTranscodeLease(id, token)).isTrue();
		assertThat(updater.renewTranscodeLease(id, token)).isFalse();
	}

	@Test
	void finalizationRequiresExactLiveAttemptToken() {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		when(mediaRepo.finalizeHlsIfProcessing(
				eq(id), eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC),
				eq(MediaStatus.PROCESSING), eq(token), any(LocalDateTime.class),
				eq(MediaStatus.READY), eq(MediaStreamingProtocol.HLS),
				eq("https://cdn/master.m3u8"), isNull(), isNull(), isNull(), isNull()))
				.thenReturn(1);

		assertThat(updater.tryFinalizeReadyHls(
				id, token, "https://cdn/master.m3u8", " ", 0, -1, null)).isTrue();
	}

	@Test
	void lateAttemptCannotMoveReplacementIntoCleanup() {
		UUID id = UUID.randomUUID();
		UUID staleToken = UUID.randomUUID();
		when(mediaRepo.markTranscodeAttemptForCleanup(
				eq(id), eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC),
				eq(MediaStatus.PROCESSING), eq(MediaStatus.HLS_CLEANUP),
				eq(staleToken), any())).thenReturn(0);

		assertThat(updater.markHlsCleanupPending(id, staleToken)).isFalse();
	}

	@Test
	void exhaustedInfrastructureAttemptUsesRetainedSourceCleanupNotPermanentDelete() {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		when(mediaRepo.markTranscodeAttemptForRetryCleanup(
				eq(id), eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC),
				eq(MediaStatus.PROCESSING), eq(MediaStatus.HLS_CLEANUP), eq(token),
				eq(3), eq(3), any(), any())).thenReturn(0);
		when(mediaRepo.markTranscodeAttemptForRetainedCleanup(
				eq(id), eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC),
				eq(MediaStatus.PROCESSING), eq(MediaStatus.HLS_CLEANUP), eq(token),
				eq(3), eq(3), any())).thenReturn(1);

		assertThat(updater.markHlsCleanupPending(id, token, 3, true)).isTrue();

		verify(mediaRepo, never()).markTranscodeAttemptForCleanup(
				any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void unstartedClaimUsesImmediateExactTokenRetryCleanup() {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
		ArgumentCaptor<LocalDateTime> cleanupNotBefore =
				ArgumentCaptor.forClass(LocalDateTime.class);
		when(mediaRepo.markTranscodeAttemptForRetryCleanup(
				eq(id), eq(MediaKind.VIDEO), eq(MediaVisibility.PUBLIC),
				eq(MediaStatus.PROCESSING), eq(MediaStatus.HLS_CLEANUP), eq(token),
				eq(1), eq(3), now.capture(), cleanupNotBefore.capture())).thenReturn(1);

		assertThat(updater.abandonClaimForRetry(id, token, 1)).isTrue();
		assertThat(cleanupNotBefore.getValue()).isEqualTo(now.getValue());
	}

	@Test
	void retryCleanupRequeuesOnlyWithinAttemptBudget() {
		UUID id = UUID.randomUUID();
		when(mediaRepo.completeHlsRetryCleanup(
				id, MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.HLS_CLEANUP, MediaStatus.TRANSCODE_QUEUED, 3))
				.thenReturn(1);

		assertThat(updater.completeHlsRetryCleanup(id))
				.isEqualTo(MediaAssetStatusUpdater.HlsRetryCleanupOutcome.REQUEUED);
		verify(mediaRepo, never()).exhaustHlsRetryCleanup(any(), any(), any(), any(), anyInt());
	}

	@Test
	void retryCleanupBecomesTerminalWhenBudgetWasExhausted() {
		UUID id = UUID.randomUUID();
		when(mediaRepo.completeHlsRetryCleanup(
				id, MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.HLS_CLEANUP, MediaStatus.TRANSCODE_QUEUED, 3))
				.thenReturn(0);
		when(mediaRepo.exhaustHlsRetryCleanup(
				id, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.HLS_CLEANUP, 3))
				.thenReturn(1);

		assertThat(updater.completeHlsRetryCleanup(id))
				.isEqualTo(MediaAssetStatusUpdater.HlsRetryCleanupOutcome.EXHAUSTED);
	}

	@Test
	void exhaustedCleanupFinishesFailedWhileRepositoryRetainsVerifiedStorageKey() {
		UUID id = UUID.randomUUID();
		when(mediaRepo.completeRetainedSourceFailure(
				id, MediaKind.VIDEO, MediaVisibility.PUBLIC,
				MediaStatus.HLS_CLEANUP, MediaStatus.FAILED)).thenReturn(1);

		assertThat(updater.completeRetainedSourceFailure(id)).isTrue();
	}

	@Test
	void cleanupTargetCarriesRetryAndHardDeadlineWithoutExposingArbitraryKey() {
		UUID id = UUID.randomUUID();
		MediaAsset asset = video(MediaStatus.HLS_CLEANUP, id);
		asset.setTranscodeRetryPending(true);
		asset.setTranscodeCleanupNotBefore(LocalDateTime.now().plusHours(1));
		when(mediaRepo.findById(id)).thenReturn(Optional.of(asset));

		var target = updater.getHlsCleanupTarget(id).orElseThrow();
		assertThat(target.retryAfterCleanup()).isTrue();
		assertThat(target.cleanupNotBefore()).isEqualTo(asset.getTranscodeCleanupNotBefore());
	}

	private static MediaAsset video(MediaStatus status, UUID id) {
		MediaAsset asset = MediaAsset.builder()
				.kind(MediaKind.VIDEO)
				.status(status)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(UUID.randomUUID())
				.mimeType("video/mp4")
				.size(123L)
				.storageKey("verified/media/" + id + "/source.mp4")
				.build();
		ReflectionTestUtils.setField(asset, "id", id);
		return asset;
	}
}
