package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.PresignedUploadWriteWindow;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;
import java.util.Optional;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaHlsCleanupDispatcherTest {

	@Mock StorageClient storageClient;
	@Mock MediaPolicy mediaPolicy;
	@Mock MediaAssetStatusUpdater statusUpdater;
	@Mock PresignedUploadWriteWindow presignedUploadWriteWindow;

	private MediaHlsCleanupDispatcher dispatcher;

	@BeforeEach
	void setUp() {
		dispatcher = new MediaHlsCleanupDispatcher(
				storageClient, mediaPolicy, statusUpdater,
				presignedUploadWriteWindow, Runnable::run);
	}

	@Test
	void cleanupDeletesAndInvalidatesBeforeTerminalTransition() {
		UUID assetId = UUID.randomUUID();
		String prefix = "media/" + assetId + "/hls";
		String sourceKey = "verified/media/" + assetId + "/source.mp4";
		when(mediaPolicy.buildHlsPrefix(assetId)).thenReturn(prefix);
		when(statusUpdater.getHlsCleanupTarget(assetId))
				.thenReturn(Optional.of(new MediaAssetStatusUpdater.HlsCleanupTarget(sourceKey)));
		when(presignedUploadWriteWindow.isSafeToDelete((java.time.LocalDateTime) null))
				.thenReturn(true);
		when(statusUpdater.completeHlsCleanup(assetId)).thenReturn(true);

		assertThat(dispatcher.submit(assetId)).isTrue();

		InOrder order = inOrder(storageClient, statusUpdater);
		order.verify(statusUpdater).getHlsCleanupTarget(assetId);
		order.verify(storageClient).deleteObject(sourceKey);
		order.verify(storageClient).deleteObject("media/" + assetId + "/source.mp4");
		order.verify(storageClient).deleteObject("quarantine/media/" + assetId + "/source.mp4");
		order.verify(storageClient).deleteFolder(prefix);
		order.verify(storageClient).invalidatePublicAsset(assetId);
		order.verify(statusUpdater).completeHlsCleanup(assetId);
		verify(statusUpdater, never()).deferHlsCleanup(assetId);
	}

	@Test
	void cleanupFailureRetainsAndDefersDurableIntent() {
		UUID assetId = UUID.randomUUID();
		String prefix = "media/" + assetId + "/hls";
		String sourceKey = "verified/media/" + assetId + "/source.mp4";
		when(mediaPolicy.buildHlsPrefix(assetId)).thenReturn(prefix);
		when(statusUpdater.getHlsCleanupTarget(assetId))
				.thenReturn(Optional.of(new MediaAssetStatusUpdater.HlsCleanupTarget(sourceKey)));
		doThrow(new IllegalStateException("storage unavailable"))
				.when(storageClient).deleteFolder(prefix);

		assertThat(dispatcher.submit(assetId)).isTrue();

		verify(statusUpdater).deferHlsCleanup(assetId);
		verify(statusUpdater, never()).completeHlsCleanup(assetId);
	}

	@Test
	void cleanupRetainsDurableIntentUntilAcceptedPutCouldNoLongerFinish() {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "verified/media/" + assetId + "/source.mp4";
		when(statusUpdater.getHlsCleanupTarget(assetId))
				.thenReturn(Optional.of(new MediaAssetStatusUpdater.HlsCleanupTarget(sourceKey)));
		when(mediaPolicy.buildHlsPrefix(assetId)).thenReturn("media/" + assetId + "/hls");
		when(presignedUploadWriteWindow.isSafeToDelete((java.time.LocalDateTime) null))
				.thenReturn(false);

		assertThat(dispatcher.submit(assetId)).isTrue();

		verify(statusUpdater).deferHlsCleanup(assetId);
		verify(statusUpdater, never()).completeHlsCleanup(assetId);
	}

	@Test
	void cleanupWhoseDurableIntentWasSupersededDoesNotTouchStorage() {
		UUID assetId = UUID.randomUUID();
		when(statusUpdater.getHlsCleanupTarget(assetId)).thenReturn(Optional.empty());

		assertThat(dispatcher.submit(assetId)).isTrue();

		verify(storageClient, never()).deleteObject(anyString());
		verify(storageClient, never()).deleteFolder(anyString());
		verify(storageClient, never()).invalidatePublicAsset(assetId);
		verify(statusUpdater, never()).completeHlsCleanup(assetId);
	}

	@Test
	void retryCleanupDeletesOnlyDerivativePrefixThenRequeuesVerifiedSource() {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "verified/media/" + assetId + "/source.mp4";
		String prefix = "media/" + assetId + "/hls";
		when(statusUpdater.getHlsCleanupTarget(assetId)).thenReturn(Optional.of(
				new MediaAssetStatusUpdater.HlsCleanupTarget(sourceKey, true)));
		when(mediaPolicy.buildHlsPrefix(assetId)).thenReturn(prefix);
		when(statusUpdater.completeHlsRetryCleanup(assetId))
				.thenReturn(MediaAssetStatusUpdater.HlsRetryCleanupOutcome.REQUEUED);

		assertThat(dispatcher.submit(assetId)).isTrue();

		verify(storageClient).deleteFolder(prefix);
		verify(storageClient).invalidatePublicAsset(assetId);
		verify(storageClient, never()).deleteObject(anyString());
		verify(statusUpdater).completeHlsRetryCleanup(assetId);
		verify(statusUpdater, never()).completeHlsCleanup(assetId);
	}

	@Test
	void expiredAttemptCleanupWaitsForDurableHardWriterDeadline() {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "verified/media/" + assetId + "/source.mp4";
		when(statusUpdater.getHlsCleanupTarget(assetId)).thenReturn(Optional.of(
				new MediaAssetStatusUpdater.HlsCleanupTarget(
						sourceKey, null, null, true, false,
						LocalDateTime.now().plusHours(1))));

		assertThat(dispatcher.submit(assetId)).isTrue();

		verify(statusUpdater).deferHlsCleanup(assetId);
		verifyNoInteractions(storageClient);
		verify(statusUpdater, never()).completeHlsRetryCleanup(assetId);
	}

	@Test
	void exhaustedInfrastructureRetryCleansDerivativesButRetainsVerifiedSource() {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "verified/media/" + assetId + "/source.mp4";
		String prefix = "media/" + assetId + "/hls";
		when(statusUpdater.getHlsCleanupTarget(assetId)).thenReturn(Optional.of(
				new MediaAssetStatusUpdater.HlsCleanupTarget(
						sourceKey, null, null, false, true, null)));
		when(mediaPolicy.buildHlsPrefix(assetId)).thenReturn(prefix);
		when(statusUpdater.completeRetainedSourceFailure(assetId)).thenReturn(true);

		assertThat(dispatcher.submit(assetId)).isTrue();

		verify(storageClient).deleteFolder(prefix);
		verify(storageClient).invalidatePublicAsset(assetId);
		verify(storageClient, never()).deleteObject(anyString());
		verify(statusUpdater).completeRetainedSourceFailure(assetId);
		verify(statusUpdater, never()).completeHlsCleanup(assetId);
	}
}
