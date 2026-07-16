package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfmpegService;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfprobeService;
import com.berkayb.soundconnect.modules.media.transcode.upload.HlsUploader;
import com.berkayb.soundconnect.modules.media.transcode.validation.VideoTranscodeRejectedException;
import com.berkayb.soundconnect.modules.media.transcode.validation.TranscodeTempBudgetManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoHlsPreflightIntegrationTest {

	@Mock StorageClient storage;
	@Mock FfmpegService ffmpeg;
	@Mock HlsUploader uploader;
	@Mock MediaAssetStatusUpdater statusUpdater;
	@Mock FfprobeService ffprobe;
	@Mock MediaPolicy mediaPolicy;
	@Mock MediaTranscodeLeaseHeartbeat leaseHeartbeat;
	@Mock TranscodeLease lease;
	@Mock TranscodeTempBudgetManager tempBudgetManager;
	@Mock TranscodeTempBudgetManager.Reservation tempReservation;
	@Mock TranscodeProperties transcodeProperties;

	@Test
	void permanentProbeOrBudgetRejectionStopsBeforeFfmpegAndKeepsLeaseFence()
			throws Exception {
		UUID assetId = UUID.randomUUID();
		UUID attemptToken = UUID.randomUUID();
		String sourceKey = "verified/media/" + assetId + "/source.mp4";
		var work = new VideoHlsWorkflow.ClaimedVideoHlsWork(
				assetId, attemptToken, sourceKey, "media/" + assetId + "/hls", 1);
		when(leaseHeartbeat.start(assetId, attemptToken)).thenReturn(lease);
		when(tempBudgetManager.reserveMaxWorkBudget()).thenReturn(tempReservation);
		when(transcodeProperties.getSourceDownloadTimeoutSec()).thenReturn(7200);
		AtomicReference<Path> workDirectory = new AtomicReference<>();
		doAnswer(invocation -> {
			Path target = invocation.getArgument(1, Path.class);
			workDirectory.set(target.getParent());
			Files.write(target, new byte[] {0, 1, 2, 3});
			return null;
		}).when(storage).downloadToFile(
				eq(sourceKey), any(Path.class), eq(Duration.ofHours(2)));
		doAnswer(invocation -> {
			assertThat(workDirectory.get()).isNotNull();
			assertThat(Files.exists(workDirectory.get())).isFalse();
			return null;
		}).when(tempReservation).close();
		when(ffprobe.probeVideo(any(Path.class)))
				.thenThrow(new VideoTranscodeRejectedException("video duration exceeds configured limit"));
		var workflow = new VideoHlsWorkflow(
				storage, ffmpeg, uploader, statusUpdater, ffprobe, mediaPolicy,
				leaseHeartbeat, tempBudgetManager, transcodeProperties);

		assertThatThrownBy(() -> workflow.processClaimed(work))
				.isInstanceOf(VideoTranscodeRejectedException.class)
				.hasMessageContaining("duration");

		verify(lease, times(3)).checkpoint();
		verify(tempReservation).close();
		verify(statusUpdater).markHlsCleanupPending(assetId, attemptToken, 1, false);
		verify(ffmpeg, never()).generateHlsLadder(any(Path.class), any(Path.class));
		verify(ffmpeg, never()).generateThumbnail(any(Path.class), any(Path.class));
		verify(uploader, never()).uploadHlsTree(any(Path.class), any(), any(Path.class));
	}
}
