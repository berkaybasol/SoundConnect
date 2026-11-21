package com.berkayb.soundconnect.modules.media.storage;

import com.berkayb.soundconnect.shared.telemetry.tracing.SafeDependencyTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class S3AssetVersionPurgerTest {
    private final S3Client s3 = mock(S3Client.class);
    private final UUID asset = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private final String prefix = "media/" + asset + "/";
    private S3AssetVersionPurger purger(int limit) {
        return new S3AssetVersionPurger(s3, SafeDependencyTelemetry.NOOP, "public", "private", "media", limit, () -> 0);
    }
    private ListObjectVersionsResponse empty() {
        return ListObjectVersionsResponse.builder().isTruncated(false).build();
    }
    private ListObjectVersionsResponse version(String key, String id) {
        return ListObjectVersionsResponse.builder().isTruncated(false)
                .versions(ObjectVersion.builder().key(key).versionId(id).build()).build();
    }
    private ListObjectVersionsResponse marker() {
        return ListObjectVersionsResponse.builder().isTruncated(false)
                .deleteMarkers(DeleteMarkerEntry.builder().key(prefix + "source").versionId("marker").build()).build();
    }

    @Test void removesVersionsAndMarkersThenVerifiesAllFiveNamespacesEmpty() {
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
                ListObjectVersionsResponse.builder().isTruncated(true)
                        .versions(ObjectVersion.builder().key(prefix + "source").versionId("null").build())
                        .deleteMarkers(DeleteMarkerEntry.builder().key(prefix + "source").versionId("marker").build()).build(),
                version(prefix + "source", "old"), marker(), marker(), empty());
        when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());
        purger(5).purge(asset);
        var deletes = org.mockito.ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3, times(3)).deleteObjects(deletes.capture());
        assertThat(deletes.getAllValues().getFirst().delete().objects()).extracting(ObjectIdentifier::versionId)
                .containsExactly("null");
        assertThat(deletes.getAllValues().getLast().delete().objects()).extracting(ObjectIdentifier::versionId)
                .containsExactly("marker");
        var lists = org.mockito.ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        verify(s3, times(13)).listObjectVersions(lists.capture());
        assertThat(lists.getAllValues()).allSatisfy(r -> {
            assertThat(r.keyMarker()).isNull();
            assertThat(r.maxKeys()).isEqualTo(1000);
            assertThat(r.overrideConfiguration().orElseThrow().apiCallTimeout()).contains(java.time.Duration.ofSeconds(5));
        });
        assertThat(lists.getAllValues()).extracting(r -> r.bucket() + ":" + r.prefix()).contains(
                "public:" + prefix, "private:" + prefix, "private:quarantine/" + prefix,
                "private:verified/" + prefix, "private:private-verified/" + prefix);
    }

    @Test void rejectsForeignAssetBeforeAnyDelete() {
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class)))
                .thenReturn(version("media/" + UUID.randomUUID() + "/source", "v1"));
        assertThatThrownBy(() -> purger(2).purge(asset)).isInstanceOf(IllegalStateException.class);
        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test void missingVersionCannotBecomeAnUnversionedDelete() {
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(version(prefix + "source", null));
        assertThatThrownBy(() -> purger(2).purge(asset)).isInstanceOf(IllegalStateException.class);
        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test void partialDeleteFailureRetainsRetryResponsibility() {
        var telemetry = mock(SafeDependencyTelemetry.class);
        var dependencyCall = mock(SafeDependencyTelemetry.Call.class);
        when(telemetry.start(SafeDependencyTelemetry.Operation.S3_DELETE_OBJECTS)).thenReturn(dependencyCall);
        when(telemetry.observe(eq(SafeDependencyTelemetry.Operation.S3_LIST_VERSIONS), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
                version(prefix + "source", "v1").toBuilder().deleteMarkers(marker().deleteMarkers()).build());
        when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder()
                .errors(S3Error.builder().key(prefix + "source").code("AccessDenied").build()).build());
        assertThatThrownBy(() -> new S3AssetVersionPurger(s3, telemetry, "public", "private", "media", 2, () -> 0)
                .purge(asset)).hasMessage("Version purge incomplete");
        verify(dependencyCall, never()).succeeded();
        verify(dependencyCall).close();
        verify(s3, times(1)).listObjectVersions(any(ListObjectVersionsRequest.class));
        var deletion = org.mockito.ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3).deleteObjects(deletion.capture());
        assertThat(deletion.getValue().delete().objects()).extracting(ObjectIdentifier::versionId).containsExactly("v1");
    }

    @Test void stuckListingIsBoundedAndNeverReportedComplete() {
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(version(prefix + "source", "v1"));
        when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());
        assertThatThrownBy(() -> purger(2).purge(asset)).hasMessage("Version purge batch limit reached");
        verify(s3, times(2)).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test void emptyTruncatedOrMissingCompletionFlagDoesNotProveAbsence() {
        for (var page : new ListObjectVersionsResponse[] {
                ListObjectVersionsResponse.builder().isTruncated(true).build(),
                ListObjectVersionsResponse.builder().build()}) {
            when(s3.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(page);
            assertThatThrownBy(() -> purger(2).purge(asset)).isInstanceOf(IllegalStateException.class);
        }
        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }

    @Test void deadlineEndsBeforeAnotherProviderCall() {
        AtomicLong clock = new AtomicLong();
        var bounded = new S3AssetVersionPurger(s3, SafeDependencyTelemetry.NOOP,
                "public", "private", "media", 2, () -> clock.getAndAdd(31_000_000_000L));
        assertThatThrownBy(() -> bounded.purge(asset)).hasMessage("Version purge deadline reached");
        verifyNoInteractions(s3);
    }

    @Test void activationIsExplicitAndNormalCleanupDoesNotPurgeVersions() {
        S3StorageClient client = new S3StorageClient();
        ReflectionTestUtils.setField(client, "s3", s3);
        client.purgeAssetVersions(asset);
        verifyNoInteractions(s3);
        ReflectionTestUtils.setField(client, "bucket", "public");
        ReflectionTestUtils.setField(client, "privateBucket", "private");
        ReflectionTestUtils.setField(client, "versionPurgeEnabled", true);
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(empty());
        client.purgeAssetVersions(asset);
        verify(s3, times(10)).listObjectVersions(any(ListObjectVersionsRequest.class));
    }

    @Test void markerOnlyPageIsTraversedBeforeAnyMarkerIsDeleted() {
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(
                marker().toBuilder().isTruncated(true).nextKeyMarker(prefix + "source").nextVersionIdMarker("marker").build(),
                version(prefix + "z", "hidden-old"), marker(), marker(), empty());
        when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());
        purger(4).purge(asset);
        var deletes = org.mockito.ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3, times(2)).deleteObjects(deletes.capture());
        assertThat(deletes.getAllValues().getFirst().delete().objects()).extracting(ObjectIdentifier::versionId)
                .containsExactly("hidden-old");
        var lists = org.mockito.ArgumentCaptor.forClass(ListObjectVersionsRequest.class);
        verify(s3, times(13)).listObjectVersions(lists.capture());
        assertThat(lists.getAllValues().get(1).keyMarker()).isEqualTo(prefix + "source");
        assertThat(lists.getAllValues().get(2).keyMarker()).isNull();
    }

    @Test void newContentDuringMarkerStageLeavesMarkerInPlace() {
        when(s3.listObjectVersions(any(ListObjectVersionsRequest.class))).thenReturn(marker(), version(prefix + "source", "late"));
        assertThatThrownBy(() -> purger(2).purge(asset)).hasMessage("Content appeared during marker purge");
        verify(s3, never()).deleteObjects(any(DeleteObjectsRequest.class));
    }
}
