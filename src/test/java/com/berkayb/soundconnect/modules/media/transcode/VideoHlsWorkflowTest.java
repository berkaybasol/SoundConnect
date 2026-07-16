package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.dto.request.VideoHlsRequest;
import com.berkayb.soundconnect.modules.media.dto.response.HlsUploadResult;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfmpegService;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfprobeService;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.VideoProbeMetadata;
import com.berkayb.soundconnect.modules.media.transcode.upload.HlsUploader;
import com.berkayb.soundconnect.modules.media.transcode.validation.VideoTranscodeRejectedException;
import com.berkayb.soundconnect.modules.media.transcode.validation.TranscodeTempBudgetManager;
import com.berkayb.soundconnect.modules.media.transcode.validation.TranscodeCapacityUnavailableException;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class VideoHlsWorkflowTest {

	@Mock StorageClient storage;
	@Mock FfmpegService ffmpeg;
	@Mock HlsUploader uploader;
	@Mock MediaAssetStatusUpdater statusUpdater;
	@Mock FfprobeService ffprobe;
	@Mock MediaPolicy mediaPolicy;
	@Mock MediaTranscodeLeaseHeartbeat heartbeat;
	@Mock TranscodeLease lease;
	@Mock TranscodeTempBudgetManager tempBudgetManager;
	@Mock TranscodeTempBudgetManager.Reservation tempReservation;
	@Mock TranscodeProperties transcodeProperties;

	VideoHlsWorkflow workflow;

	@BeforeEach
	void setup() {
		lenient().when(transcodeProperties.getSourceDownloadTimeoutSec()).thenReturn(7200);
		workflow = new VideoHlsWorkflow(
				storage, ffmpeg, uploader, statusUpdater, ffprobe, mediaPolicy,
				heartbeat, tempBudgetManager, transcodeProperties);
	}

	@Test
	void happyPathUsesDurableClaimHeartbeatStrictProbeAndTokenFinalizer() throws Exception {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		stubClaim(id, token, 1);
		stubDownloadedSource(id);
		when(heartbeat.start(id, token)).thenReturn(lease);
		when(ffprobe.probeVideo(any(Path.class)))
				.thenReturn(new VideoProbeMetadata(123.2, 1920, 1080, 30.0));
		when(uploader.uploadHlsTree(any(), eq("media/" + id + "/hls"), any()))
				.thenReturn(new HlsUploadResult("https://cdn/master.m3u8", "https://cdn/thumb.jpg", 7));
		when(statusUpdater.tryFinalizeReadyHls(
				eq(id), eq(token), anyString(), anyString(), any(), any(), any()))
				.thenReturn(true);

		workflow.process(request(id));

		InOrder order = inOrder(statusUpdater, heartbeat, storage, ffprobe, ffmpeg, uploader);
		order.verify(statusUpdater).tryClaimQueuedTranscode(id);
		order.verify(heartbeat).start(id, token);
		order.verify(storage).downloadToFile(
				eq("verified/media/" + id + "/source.mp4"), any(), eq(Duration.ofHours(2)));
		order.verify(ffprobe).probeVideo(any());
		order.verify(ffmpeg).generateHlsLadder(any(), any());
		order.verify(ffmpeg).generateThumbnail(any(), any());
		order.verify(uploader).uploadHlsTree(any(), eq("media/" + id + "/hls"), any());
		order.verify(statusUpdater).tryFinalizeReadyHls(
				id, token, "https://cdn/master.m3u8", "https://cdn/thumb.jpg", 124, 1920, 1080);
		verify(lease, times(7)).checkpoint();
		verify(lease).close();
	}

	@Test
	void strictAdmissionFailureNeverStartsFfmpegAndIsTerminal() throws Exception {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		stubClaim(id, token, 1);
		stubDownloadedSource(id);
		when(heartbeat.start(id, token)).thenReturn(lease);
		when(ffprobe.probeVideo(any()))
				.thenThrow(new VideoTranscodeRejectedException("duration over limit"));

		assertThatThrownBy(() -> workflow.process(request(id)))
				.isInstanceOf(VideoTranscodeRejectedException.class);

		verifyNoInteractions(ffmpeg, uploader);
		verify(statusUpdater).markHlsCleanupPending(id, token, 1, false);
		verify(lease).close();
	}

	@Test
	void transientInfrastructureFailurePreservesSourceForBoundedRetry() throws Exception {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		stubClaim(id, token, 2);
		when(heartbeat.start(id, token)).thenReturn(lease);
		doThrow(new IllegalStateException("S3 unavailable"))
				.when(storage).downloadToFile(
						eq("verified/media/" + id + "/source.mp4"), any(), eq(Duration.ofHours(2)));

		assertThatThrownBy(() -> workflow.process(request(id)))
				.isInstanceOf(IllegalStateException.class);

		verify(statusUpdater).markHlsCleanupPending(id, token, 2, true);
		verifyNoInteractions(ffprobe, ffmpeg, uploader);
	}

	@Test
	void temporaryDiskCapacityFailureIsRetryableNotPermanentContentRejection() throws Exception {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		stubClaim(id, token, 1);
		stubDownloadedSource(id);
		when(heartbeat.start(id, token)).thenReturn(lease);
		when(ffprobe.probeVideo(any()))
				.thenThrow(new TranscodeCapacityUnavailableException("temp disk busy"));

		assertThatThrownBy(() -> workflow.process(request(id)))
				.isInstanceOf(TranscodeCapacityUnavailableException.class);

		verify(statusUpdater).markHlsCleanupPending(id, token, 1, true);
		verifyNoInteractions(ffmpeg, uploader);
	}

	@Test
	void leaseLossStopsBeforeUploadAndLetsDurableRecoveryOwnState() throws Exception {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		stubClaim(id, token, 1);
		when(heartbeat.start(id, token)).thenReturn(lease);
		doThrow(new TranscodeLeaseLostException()).when(lease).checkpoint();

		workflow.process(request(id));

		verifyNoInteractions(storage, ffprobe, ffmpeg, uploader);
		verify(statusUpdater, never()).markHlsCleanupPending(any(), any(), anyInt(), anyBoolean());
	}

	@Test
	void duplicateOrLiveDeliveryDoesNoExpensiveWork() throws Exception {
		UUID id = UUID.randomUUID();
		when(statusUpdater.tryClaimQueuedTranscode(id)).thenReturn(Optional.empty());

		workflow.process(request(id));

		verifyNoInteractions(storage, ffprobe, ffmpeg, uploader, heartbeat);
	}

	@Test
	void finalizationFenceLeavesCleanupToDurableState() throws Exception {
		UUID id = UUID.randomUUID();
		UUID token = UUID.randomUUID();
		stubClaim(id, token, 1);
		stubDownloadedSource(id);
		when(heartbeat.start(id, token)).thenReturn(lease);
		when(ffprobe.probeVideo(any())).thenReturn(new VideoProbeMetadata(10.0, 640, 360, 30.0));
		when(uploader.uploadHlsTree(any(), anyString(), any()))
				.thenReturn(new HlsUploadResult("https://cdn/master.m3u8", null, 4));
		when(statusUpdater.tryFinalizeReadyHls(
				eq(id), eq(token), anyString(), isNull(), any(), any(), any())).thenReturn(false);

		workflow.process(request(id));

		verify(storage, never()).deleteFolder(anyString());
		verify(statusUpdater, never()).markHlsCleanupPending(any(), any(), anyInt(), anyBoolean());
	}

	@Test
	void invalidRequestFailsBeforeDatabaseOrWorkerAdmission() {
		assertThatThrownBy(() -> workflow.process(new VideoHlsRequest("", "k", "p", "t", 1)))
				.isInstanceOf(SoundConnectException.class);
		verifyNoInteractions(statusUpdater, heartbeat, storage, ffprobe, ffmpeg, uploader, mediaPolicy);
	}

	private void stubClaim(UUID id, UUID token, int attempt) throws Exception {
		when(statusUpdater.tryClaimQueuedTranscode(id)).thenReturn(Optional.of(
				new MediaAssetStatusUpdater.TranscodeClaim(
						id, token, "verified/media/" + id + "/source.mp4", attempt,
						LocalDateTime.now().plusMinutes(15), LocalDateTime.now().plusHours(12))));
		when(mediaPolicy.buildHlsPrefix(id)).thenReturn("media/" + id + "/hls");
		lenient().when(tempBudgetManager.reserveMaxWorkBudget()).thenReturn(tempReservation);
	}

	private void stubDownloadedSource(UUID id) throws Exception {
		doAnswer(invocation -> {
			Path target = invocation.getArgument(1);
			Files.createDirectories(target.getParent());
			Files.writeString(target, "dummy mp4");
			return null;
		}).when(storage).downloadToFile(
				eq("verified/media/" + id + "/source.mp4"), any(), eq(Duration.ofHours(2)));
	}

	private static VideoHlsRequest request(UUID id) {
		return new VideoHlsRequest(
				id.toString(), "message/source.mp4", "message/hls", "2025-09-01T12:00:00Z", 1);
	}
}
