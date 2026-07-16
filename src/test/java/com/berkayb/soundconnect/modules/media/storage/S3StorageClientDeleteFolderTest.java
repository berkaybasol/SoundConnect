package com.berkayb.soundconnect.modules.media.storage;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3StorageClientDeleteFolderTest {

	@Test
	void deleteFolderUsesOneBatchInsteadOfSequentialObjectRequests() {
		S3Client s3 = mock(S3Client.class);
		S3StorageClient client = new S3StorageClient();
		ReflectionTestUtils.setField(client, "bucket", "public-media");
		ReflectionTestUtils.setField(client, "privateBucket", "private-media");
		ReflectionTestUtils.setField(client, "s3", s3);

		when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
				ListObjectsV2Response.builder()
						.contents(
								S3Object.builder().key("media/id/hls/master.m3u8").build(),
								S3Object.builder().key("media/id/hls/720p/segment.m4s").build())
						.isTruncated(false)
						.build());
		when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
				.thenReturn(DeleteObjectsResponse.builder().build());

		client.deleteFolder("media/id/hls");

		var request = org.mockito.ArgumentCaptor.forClass(DeleteObjectsRequest.class);
		verify(s3).deleteObjects(request.capture());
		assertThat(request.getValue().bucket()).isEqualTo("public-media");
		assertThat(request.getValue().delete().objects())
				.extracting(object -> object.key())
				.containsExactly(
						"media/id/hls/master.m3u8",
						"media/id/hls/720p/segment.m4s");
	}

	@Test
	void attemptSweepDeletesOldAttemptButRetainsWinnerSourceAndThumbnailSubtree() {
		S3Client s3 = mock(S3Client.class);
		S3StorageClient client = new S3StorageClient();
		ReflectionTestUtils.setField(client, "bucket", "public-media");
		ReflectionTestUtils.setField(client, "privateBucket", "private-media");
		ReflectionTestUtils.setField(client, "s3", s3);
		String attemptA = "media/id/attempts/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
		String attemptB = "media/id/attempts/bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

		when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(
				ListObjectsV2Response.builder()
						.contents(
								S3Object.builder().key(attemptA + "/source.png").build(),
								S3Object.builder().key(attemptA + "/thumbnail.jpg").build(),
								S3Object.builder().key(attemptB + "/source.png").build(),
								S3Object.builder().key(attemptB + "/thumbnail.jpg").build())
						.isTruncated(false)
						.build());
		when(s3.deleteObjects(any(DeleteObjectsRequest.class)))
				.thenReturn(DeleteObjectsResponse.builder().build());

		client.deleteFolderExceptPrefix("media/id/attempts", attemptB);

		var request = org.mockito.ArgumentCaptor.forClass(DeleteObjectsRequest.class);
		verify(s3).deleteObjects(request.capture());
		assertThat(request.getValue().delete().objects())
				.extracting(object -> object.key())
				.containsExactly(attemptA + "/source.png", attemptA + "/thumbnail.jpg");
	}
}
