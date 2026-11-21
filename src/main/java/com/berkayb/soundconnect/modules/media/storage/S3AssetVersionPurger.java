package com.berkayb.soundconnect.modules.media.storage;

import com.berkayb.soundconnect.shared.telemetry.tracing.SafeDependencyTelemetry;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.time.Duration;
import java.util.*;
import java.util.function.LongSupplier;

import static com.berkayb.soundconnect.shared.telemetry.tracing.SafeDependencyTelemetry.Operation.*;

/** Bounded permanent deletion of one UUID namespace; never used for normal GC. */
final class S3AssetVersionPurger {
    private final S3Client s3;
    private final SafeDependencyTelemetry telemetry;
    private final String publicBucket, privateBucket, root;
    private final int maxBatches;
    private final LongSupplier nanoTime;

    S3AssetVersionPurger(S3Client s3, SafeDependencyTelemetry telemetry,
                        String publicBucket, String privateBucket, String root) {
        this(s3, telemetry, publicBucket, privateBucket, root, 100, System::nanoTime);
    }

    S3AssetVersionPurger(S3Client s3, SafeDependencyTelemetry telemetry,
                        String publicBucket, String privateBucket, String root,
                        int maxBatches, LongSupplier nanoTime) {
        this.s3 = Objects.requireNonNull(s3);
        this.telemetry = Objects.requireNonNull(telemetry);
        if (publicBucket == null || privateBucket == null || publicBucket.isBlank()
                || privateBucket.isBlank() || publicBucket.equals(privateBucket)
                || root == null || !root.matches("[A-Za-z0-9][A-Za-z0-9_-]*(/[A-Za-z0-9][A-Za-z0-9_-]*)*")
                || maxBatches < 1 || maxBatches > 100) {
            throw new IllegalArgumentException("Invalid permanent deletion scope");
        }
        this.publicBucket = publicBucket;
        this.privateBucket = privateBucket;
        this.root = root;
        this.maxBatches = maxBatches;
        this.nanoTime = nanoTime;
    }

    void purge(UUID assetId) {
        Objects.requireNonNull(assetId, "assetId");
        String asset = root + "/" + assetId + "/";
        var scopes = List.of(new Scope(publicBucket, asset), new Scope(privateBucket, asset),
                new Scope(privateBucket, "quarantine/" + asset),
                new Scope(privateBucket, "verified/" + asset),
                new Scope(privateBucket, "private-verified/" + asset));
        long started = nanoTime.getAsLong();
        Budget budget = new Budget(started);
        for (Scope scope : scopes) {
            // Keep delete markers until ALL content versions are gone. Removing
            // markers in a partial multi-delete could expose an older public object.
            String keyMarker = null, versionMarker = null;
            Set<String> cursors = new HashSet<>();
            while (true) {
                var page = list(scope, keyMarker, versionMarker, budget);
                var versions = page.versions().stream().map(v -> identifier(scope, v.key(), v.versionId())).toList();
                if (!versions.isEmpty()) {
                    delete(scope, versions, budget);
                    // Restart after mutation; pagination markers may reference deleted rows.
                    keyMarker = null; versionMarker = null; cursors.clear();
                } else if (!page.isTruncated()) {
                    break; // Traversed all marker-only pages; content is absent.
                } else {
                    keyMarker = page.nextKeyMarker(); versionMarker = page.nextVersionIdMarker();
                    if (keyMarker == null || !keyMarker.startsWith(scope.prefix)
                            || versionMarker == null || versionMarker.isBlank()
                            || !cursors.add(keyMarker + "\n" + versionMarker)) {
                        throw new IllegalStateException("Invalid version pagination");
                    }
                }
            }
            while (true) {
                var page = list(scope, null, null, budget);
                if (page.hasVersions()) throw new IllegalStateException("Content appeared during marker purge");
                var markers = page.deleteMarkers().stream().map(v -> identifier(scope, v.key(), v.versionId())).toList();
                if (markers.isEmpty()) break; // Fresh empty read, not just delete ACK.
                delete(scope, markers, budget);
            }
        }
    }

    private ListObjectVersionsResponse list(Scope scope, String key, String version, Budget budget) {
        if (++budget.pages > 2 * maxBatches + 10) throw new IllegalStateException("Version purge page limit reached");
        var request = ListObjectVersionsRequest.builder().bucket(scope.bucket).prefix(scope.prefix)
                .keyMarker(key).versionIdMarker(version).maxKeys(1000)
                .overrideConfiguration(limits(budget.started)).build();
        var page = telemetry.observe(S3_LIST_VERSIONS, () -> s3.listObjectVersions(request));
        if (page == null || page.isTruncated() == null || page.hasCommonPrefixes()) {
            throw new IllegalStateException("Invalid version listing");
        }
        List<ObjectIdentifier> all = new ArrayList<>();
        page.versions().forEach(v -> all.add(identifier(scope, v.key(), v.versionId())));
        page.deleteMarkers().forEach(v -> all.add(identifier(scope, v.key(), v.versionId())));
        if (all.size() > 1000 || new HashSet<>(all).size() != all.size()
                || (all.isEmpty() && page.isTruncated())) throw new IllegalStateException("Invalid version batch");
        return page;
    }

    private void delete(Scope scope, List<ObjectIdentifier> objects, Budget budget) {
        if (++budget.batches > maxBatches) throw new IllegalStateException("Version purge batch limit reached");
        var request = DeleteObjectsRequest.builder().bucket(scope.bucket)
                .delete(Delete.builder().objects(objects).quiet(true).build())
                .overrideConfiguration(limits(budget.started)).build();
        try (var call = telemetry.start(S3_DELETE_OBJECTS)) {
            var result = s3.deleteObjects(request);
            if (result == null || result.hasErrors()) throw new IllegalStateException("Version purge incomplete");
            call.succeeded();
        }
    }

    private static final class Budget {
        final long started;
        int pages, batches;
        Budget(long started) { this.started = started; }
    }

    private ObjectIdentifier identifier(Scope scope, String key, String version) {
        if (key == null || !key.startsWith(scope.prefix) || version == null || version.isBlank()) {
            throw new IllegalStateException("Version listing escaped asset scope or omitted version ID");
        }
        // The literal "null" is the valid version ID for pre-versioning objects.
        return ObjectIdentifier.builder().key(key).versionId(version).build();
    }

    private AwsRequestOverrideConfiguration limits(long started) {
        long remaining = Duration.ofSeconds(30).toNanos() - (nanoTime.getAsLong() - started);
        if (remaining < Duration.ofMillis(1).toNanos()) {
            throw new IllegalStateException("Version purge deadline reached");
        }
        Duration timeout = Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(5).toNanos()));
        return AwsRequestOverrideConfiguration.builder().apiCallTimeout(timeout)
                .apiCallAttemptTimeout(timeout).build();
    }

    private record Scope(String bucket, String prefix) { }
}
