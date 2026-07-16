package com.berkayb.soundconnect.modules.media.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class S3StorageClientDownloadTimeoutTest {

	@TempDir Path tempDir;

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void largeVideoAndExplicitImageDownloadsUseIndependentPerRequestDeadlines() {
		S3Client s3 = mock(S3Client.class);
		S3StorageClient client = new S3StorageClient();
		ReflectionTestUtils.setField(client, "s3", s3);
		ReflectionTestUtils.setField(client, "bucket", "public-bucket");
		ReflectionTestUtils.setField(client, "privateBucket", "private-bucket");

		client.downloadToFile(
				"verified/media/id/source.mp4",
				tempDir.resolve("video.mp4"),
				Duration.ofHours(2));
		client.downloadToFile(
				"verified/media/id/source.jpg",
				tempDir.resolve("image.jpg"),
				Duration.ofSeconds(30));

		ArgumentCaptor<GetObjectRequest> requestCaptor =
				ArgumentCaptor.forClass(GetObjectRequest.class);
		verify(s3, times(2)).getObject(
				requestCaptor.capture(), any(ResponseTransformer.class));

		var videoOverride = requestCaptor.getAllValues().get(0)
				.overrideConfiguration().orElseThrow();
		var imageOverride = requestCaptor.getAllValues().get(1)
				.overrideConfiguration().orElseThrow();
		assertThat(videoOverride.apiCallTimeout()).contains(Duration.ofHours(2));
		assertThat(videoOverride.apiCallAttemptTimeout()).contains(Duration.ofHours(2));
		assertThat(imageOverride.apiCallTimeout()).contains(Duration.ofSeconds(30));
		assertThat(imageOverride.apiCallAttemptTimeout()).contains(Duration.ofSeconds(30));
	}
}
