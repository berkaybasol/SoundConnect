// src/test/java/com/berkayb/soundconnect/modules/media/transcode/VideoHlsWorkflowTest.java
package com.berkayb.soundconnect.modules.media.transcode;

import com.berkayb.soundconnect.modules.media.dto.request.VideoHlsRequest;
import com.berkayb.soundconnect.modules.media.dto.response.HlsUploadResult;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfmpegService;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfprobeService;
import com.berkayb.soundconnect.modules.media.transcode.upload.HlsUploader;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("unit")
@ExtendWith(MockitoExtension.class)
class VideoHlsWorkflowTest {
	
	@Mock StorageClient storage;
	@Mock FfmpegService ffmpeg;
	@Mock HlsUploader uploader;
	@Mock MediaAssetStatusUpdater statusUpdater;
	@Mock FfprobeService ffprobe;
	
	@Captor ArgumentCaptor<Path> outDirCap;
	@Captor ArgumentCaptor<String> prefixCap;
	
	VideoHlsWorkflow workflow;
	
	@BeforeEach
	void setup() {
		workflow = new VideoHlsWorkflow(storage, ffmpeg, uploader, statusUpdater);
		// VideoHlsWorkflow içinde ffprobe constructor'a eklenmemiş; reflection ile enjekte ediyoruz.
		ReflectionTestUtils.setField(workflow, "ffprobe", ffprobe);
	}
	
	private static VideoHlsRequest makeReq(UUID id) {
		return new VideoHlsRequest(
				id.toString(),
				"media/" + id + "/source.mp4",
				"media/" + id + "/hls",
				"2025-09-01T12:00:00Z",
				1
		);
	}
	
	@Test
	void process_happyPath_calls_services_and_marksReady() throws Exception {
		UUID id = UUID.randomUUID();
		VideoHlsRequest req = makeReq(id);
		
		// storage.downloadToFile(target) çağrısında hedef dosyayı var ediyoruz
		doAnswer(inv -> {
			Path target = inv.getArgument(1, Path.class);
			Files.createDirectories(target.getParent());
			Files.writeString(target, "dummy mp4 bytes");
			return null;
		}).when(storage).downloadToFile(eq(req.sourceKey()), any(Path.class));
		
		// ffprobe meta
		Map<String, Integer> meta = new HashMap<>();
		meta.put("durationSeconds", 123);
		meta.put("width", 1920);
		meta.put("height", 1080);
		when(ffprobe.probe(any(Path.class))).thenReturn(meta);
		
		// ffmpeg işler sorunsuz
		doNothing().when(ffmpeg).generateHlsLadder(any(Path.class), any(Path.class));
		doNothing().when(ffmpeg).generateThumbnail(any(Path.class), any(Path.class));
		
		// uploader playback/thumbnail döner
		when(uploader.uploadHlsTree(outDirCap.capture(), prefixCap.capture(), any(Path.class)))
				.thenReturn(new HlsUploadResult("https://cdn/hls/master.m3u8", "https://cdn/hls/thumbnail.jpg", 7));
		
		// act
		workflow.process(req);
		
		// assert: status flow
		InOrder inOrder = inOrder(statusUpdater, storage, ffprobe, ffmpeg, uploader, statusUpdater);
		inOrder.verify(statusUpdater).markProcessing(eq(id));
		inOrder.verify(storage).downloadToFile(eq(req.sourceKey()), any(Path.class));
		inOrder.verify(ffprobe).probe(any(Path.class));
		inOrder.verify(ffmpeg).generateHlsLadder(any(Path.class), any(Path.class));
		inOrder.verify(ffmpeg).generateThumbnail(any(Path.class), any(Path.class));
		inOrder.verify(uploader).uploadHlsTree(any(Path.class), eq("media/" + id + "/hls"), any(Path.class));
		inOrder.verify(statusUpdater).markReadyHls(
				eq(id),
				eq("https://cdn/hls/master.m3u8"),
				eq("https://cdn/hls/thumbnail.jpg"),
				eq(123), eq(1920), eq(1080)
		);
		
		// outDir ve prefix gerçekten set edilmiş mi?
		assertThat(outDirCap.getValue()).isNotNull();
		assertThat(prefixCap.getValue()).isEqualTo("media/" + id + "/hls");
	}
	
	@Test
	void process_whenFfprobeFails_should_continue_with_nullMeta() throws Exception {
		UUID id = UUID.randomUUID();
		VideoHlsRequest req = makeReq(id);
		
		doAnswer(inv -> {
			Path target = inv.getArgument(1, Path.class);
			Files.createDirectories(target.getParent());
			Files.writeString(target, "dummy mp4 bytes");
			return null;
		}).when(storage).downloadToFile(eq(req.sourceKey()), any(Path.class));
		
		// ffprobe patlasın ama workflow devam etsin
		when(ffprobe.probe(any(Path.class))).thenThrow(new RuntimeException("ffprobe down"));
		
		doNothing().when(ffmpeg).generateHlsLadder(any(Path.class), any(Path.class));
		doNothing().when(ffmpeg).generateThumbnail(any(Path.class), any(Path.class));
		when(uploader.uploadHlsTree(any(Path.class), anyString(), any(Path.class)))
				.thenReturn(new HlsUploadResult("https://cdn/hls/master.m3u8", null, 5));
		
		workflow.process(req);
		
		// duration/width/height null gidebilir
		verify(statusUpdater).markReadyHls(eq(id), eq("https://cdn/hls/master.m3u8"), isNull(), isNull(), isNull(), isNull());
	}
	
	@Test
	void process_invalidRequest_should_throw() {
		// assetId boş
		assertThatThrownBy(() -> workflow.process(new VideoHlsRequest("", "k", "p", "t", 1)))
				.isInstanceOf(SoundConnectException.class);
		
		// sourceKey boş
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> workflow.process(new VideoHlsRequest(id.toString(), " ", "p", "t", 1)))
				.isInstanceOf(SoundConnectException.class);
		
		// hlsPrefix boş
		assertThatThrownBy(() -> workflow.process(new VideoHlsRequest(id.toString(), "k", "", "t", 1)))
				.isInstanceOf(SoundConnectException.class);
		
		verifyNoInteractions(storage, ffmpeg, uploader, statusUpdater, ffprobe);
	}
	
	@Test
	void process_whenFfmpegFails_marksFailed_andRethrows() throws Exception {
		UUID id = UUID.randomUUID();
		VideoHlsRequest req = makeReq(id);
		
		doAnswer(inv -> {
			Path target = inv.getArgument(1, Path.class);
			Files.createDirectories(target.getParent());
			Files.writeString(target, "dummy mp4 bytes");
			return null;
		}).when(storage).downloadToFile(eq(req.sourceKey()), any(Path.class));
		
		when(ffprobe.probe(any(Path.class))).thenReturn(Map.of("durationSeconds", 10, "width", 640, "height", 360));
		
		doThrow(new RuntimeException("ffmpeg boom"))
				.when(ffmpeg).generateHlsLadder(any(Path.class), any(Path.class));
		
		assertThatThrownBy(() -> workflow.process(req))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("ffmpeg boom");
		
		verify(statusUpdater).markProcessing(eq(id));
		verify(statusUpdater).markFailed(eq(id));
		verifyNoInteractions(uploader); // upload aşamasına gelmemeli
	}
}