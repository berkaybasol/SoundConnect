package com.berkayb.soundconnect.shared.telemetry.tracing;

import com.berkayb.soundconnect.modules.media.storage.S3StorageClient;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClientImpl;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.reactive.function.client.WebClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProviderDependencyTelemetryTest {
    @Test void realMailHttpAcceptedAndFailureHaveOnlyFixedMetadataOnOtlpWire() throws Exception {
        try (var fixture = new DependencyTelemetryFixture(); var provider = new MockWebServer()) {
            provider.start();
            provider.enqueue(new MockResponse().setResponseCode(202));
            provider.enqueue(new MockResponse().setResponseCode(503).setBody("private-provider-body"));
            var mail = new MailSenderClientImpl(mock(MailJobHelper.class),
                    WebClient.builder().baseUrl(provider.url("/private-path").toString())
                            .defaultHeader("Authorization", "Bearer private-provider-token").build());
            mail.setDependencyTelemetry(fixture.telemetry);
            ReflectionTestUtils.setField(mail, "fromEmail", "private-sender@example.invalid");
            ReflectionTestUtils.setField(mail, "fromName", "private-sender-name");
            ReflectionTestUtils.setField(mail, "readTimeoutSec", 2);
            mail.send("private-recipient@example.invalid", "private-subject", "private-mail-body", null);
            assertThatThrownBy(() -> mail.send("private-recipient@example.invalid", "private-subject", "private-mail-body", null))
                    .isInstanceOf(HttpServerErrorException.class);
            assertThat(provider.getRequestCount()).isEqualTo(2);
            assertThat(fixture.meters.get("soundconnect.dependency.duration").tags("provider", "mailersend", "operation", "send", "result", "accepted").timer().count()).isEqualTo(1);
            assertThat(fixture.meters.get("soundconnect.dependency.duration").tags("provider", "mailersend", "operation", "send", "result", "error").timer().count()).isEqualTo(1);
            var exports = fixture.exports();
            assertThat(exports).isNotEmpty();
            assertThat(exports.toString()).contains("mailersend send", "accepted", "error")
                    .doesNotContain("private-", "Authorization", "localhost", "exception", "url");
        }
    }

    @Test void actualStorageBoundaryTreatsPartialBatchDeleteAsErrorAndPresignUrlAsLocal() throws Exception {
        try (var fixture = new DependencyTelemetryFixture()) {
            var s3 = mock(S3Client.class);
            var storage = new S3StorageClient();
            storage.setDependencyTelemetry(fixture.telemetry);
            ReflectionTestUtils.setField(storage, "s3", s3);
            ReflectionTestUtils.setField(storage, "bucket", "private-bucket-name");
            ReflectionTestUtils.setField(storage, "privateBucket", "private-protected-bucket");
            ReflectionTestUtils.setField(storage, "region", "eu-central-1");
            when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(ListObjectsV2Response.builder()
                    .contents(S3Object.builder().key("media/private-user/secret.png").build()).isTruncated(false).build());
            when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder()
                    .errors(S3Error.builder().key("media/private-user/secret.png").code("AccessDenied").message("private-provider-message").build()).build());
            assertThatThrownBy(() -> storage.deleteFolder("media/private-user"))
                    .isInstanceOf(IllegalStateException.class);
            when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(HeadObjectResponse.builder()
                    .contentLength(7L).contentType("private-content-type").eTag("private-etag").build());
            assertThat(storage.getObjectMetadata("media/private-user/secret.png")).isPresent();
            storage.publicUrl("media/private-user/secret.png");
            verify(s3, times(1)).deleteObjects(any(DeleteObjectsRequest.class));
            assertThat(fixture.meters.getMeters()).hasSize(3);
            assertThat(fixture.meters.get("soundconnect.dependency.duration").tags("operation", "delete_objects", "result", "error").timer().count()).isEqualTo(1);
            assertThat(fixture.meters.get("soundconnect.dependency.duration").tags("operation", "list_objects", "result", "success").timer().count()).isEqualTo(1);
            var exports = fixture.exports();
            assertThat(exports).isNotEmpty();
            assertThat(exports.toString()).contains("s3 delete_objects", "s3 head_object", "s3 list_objects")
                    .doesNotContain("private-", "secret.png", "AccessDenied", "exception", "http.url");
        }
    }
}
