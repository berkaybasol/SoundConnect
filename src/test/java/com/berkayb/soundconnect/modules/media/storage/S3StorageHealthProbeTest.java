package com.berkayb.soundconnect.modules.media.storage;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3StorageHealthProbeTest {
    private S3StorageClient client(S3Client api) {
        S3StorageClient storage = new S3StorageClient();
        ReflectionTestUtils.setField(storage, "s3", api);
        ReflectionTestUtils.setField(storage, "bucket", "public-bucket");
        ReflectionTestUtils.setField(storage, "privateBucket", "private-bucket");
        return storage;
    }

    @Test void probesBothConfiguredBucketsWithoutReadingOrWritingObjectsAndBoundsEachCall() {
        S3Client api = mock(S3Client.class);
        assertThat(client(api).probeReadAccess(Duration.ofMillis(1500))).isEqualTo(StorageClient.ReadAccess.AVAILABLE);
        var requests = ArgumentCaptor.forClass(HeadBucketRequest.class);
        verify(api, times(2)).headBucket(requests.capture());
        assertThat(requests.getAllValues()).extracting(HeadBucketRequest::bucket)
                .containsExactly("public-bucket", "private-bucket");
        for (var request : requests.getAllValues()) {
            var timeout = request.overrideConfiguration().orElseThrow();
            assertThat(timeout.apiCallTimeout()).contains(Duration.ofMillis(750));
            assertThat(timeout.apiCallAttemptTimeout()).contains(Duration.ofMillis(750));
        }
        verifyNoMoreInteractions(api);
    }

    @Test void permissionLackIsUnverifiedAndServerFailureIsUnavailable() {
        for (int code : new int[] {401,403,500,503}) {
            S3Client api = mock(S3Client.class);
            when(api.headBucket(any(HeadBucketRequest.class))).thenThrow(
                    S3Exception.builder().statusCode(code).message("must-not-be-published").build());
            assertThat(client(api).probeReadAccess(Duration.ofMillis(1500))).isEqualTo(
                    code < 500 ? StorageClient.ReadAccess.UNVERIFIED : StorageClient.ReadAccess.UNAVAILABLE);
            verify(api).headBucket(any(HeadBucketRequest.class));
            verifyNoMoreInteractions(api);
        }
    }

    @Test void invalidTimeoutNeverDispatches() {
        S3Client api = mock(S3Client.class);
        var storage = client(api);
        assertThat(storage.probeReadAccess(null)).isEqualTo(StorageClient.ReadAccess.UNVERIFIED);
        assertThat(storage.probeReadAccess(Duration.ofSeconds(9))).isEqualTo(StorageClient.ReadAccess.UNVERIFIED);
        assertThat(storage.probeReadAccess(Duration.ZERO)).isEqualTo(StorageClient.ReadAccess.UNVERIFIED);
        verifyNoInteractions(api);
    }
}
