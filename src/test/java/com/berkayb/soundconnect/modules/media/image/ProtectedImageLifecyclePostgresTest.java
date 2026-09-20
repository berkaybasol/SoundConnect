package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.deletion.*;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.service.MediaAssetReferenceGuard;
import com.berkayb.soundconnect.modules.media.storage.*;
import com.berkayb.soundconnect.modules.media.support.MediaJpaPostgresFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual PostgreSQL commits/CAS; only object storage and native image conversion are controlled. */
class ProtectedImageLifecyclePostgresTest extends MediaJpaPostgresFixture {
    MemoryStorage storage;
    ImageThumbnailService thumbnails;
    ImageVariantBackfillFinalizer finalizer;
    ImageVariantBackfillService workflow;
    MediaDeletionWorker deletion;

    @BeforeEach
    void configureStorage() {
        storage = new MemoryStorage();
        ImageThumbnailProcessor processor = (source, target) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Files.write(target, new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, (byte) 0xff, (byte) 0xd9});
            return new ProcessedImageThumbnail(1600, 900, 960, 540);
        };
        thumbnails = new ImageThumbnailService(storage, processor, new MediaImageVariantProperties());
        finalizer = context.getBean(ImageVariantBackfillFinalizer.class);
        workflow = new ImageVariantBackfillService(assets, thumbnails, finalizer);
        deletion = new MediaDeletionWorker(context.getBean(MediaDeletionStateService.class), storage,
                mock(MediaPolicy.class), new PresignedUploadWriteWindow(900, Duration.ofMinutes(1), Duration.ofHours(24)),
                context.getBean(MediaAssetReferenceGuard.class));
    }

    @Test
    void uploadedPrivateVariantSurvivesCommitFailureThenDurableBackfillRepairsIt() throws Exception {
        var listing = draft(); var photo = image(listing.id());
        seed(photo.getStorageKey());
        installDeferredCommitFault();

        assertThatThrownBy(() -> workflow.backfillOne(photo.getId())).isInstanceOf(RuntimeException.class);

        String thumbnail = ImageThumbnailService.protectedThumbnailKeyFor(photo.getStorageKey());
        assertThat(storage.objects).containsKey(thumbnail);
        assertThat(asset(photo.getId()).getThumbnailStorageKey()).isNull();
        assertThat(assets.findIdsMissingThumbnail(MediaKind.IMAGE, MediaStatus.READY, PageRequest.of(0, 10)))
                .contains(photo.getId());
        assertThat(storage.deleted).isEmpty(); // An ambiguous commit must never delete another node's winner.

        jdbc.getJdbcTemplate().update("update test_thumbnail_fault set enabled=false");
        assertThat(workflow.backfillOne(photo.getId())).isTrue();

        var saved = asset(photo.getId());
        assertThat(saved.getThumbnailStorageKey()).isEqualTo(thumbnail);
        assertThat(saved.getThumbnailUrl()).isNull();
        assertThat(saved.getSourceUrl()).isNull();
        assertThat(saved.getWidth()).isEqualTo(1600);
        assertThat(saved.getHeight()).isEqualTo(900);
        assertThat(storage.objects).containsKeys(photo.getStorageKey(), thumbnail);
        assertThat(storage.puts.get()).isEqualTo(2);
        assertThat(storage.cacheControl.get(thumbnail)).isEqualTo("private,no-store,max-age=0");
    }

    @Test
    void lostReplyAfterSuccessfulDatabaseCommitDoesNotDeleteTheCommittedWinner() throws Exception {
        var photo = image(draft().id()); seed(photo.getStorageKey());
        var uncertain = mock(ImageVariantBackfillFinalizer.class);
        when(uncertain.attachIfStillEligible(any(), anyString(), any())).thenAnswer(call -> {
            assertThat(finalizer.attachIfStillEligible(call.getArgument(0), call.getArgument(1), call.getArgument(2))).isTrue();
            throw new TransactionSystemException("Injected loss of commit acknowledgement");
        });
        var uncertainWorkflow = new ImageVariantBackfillService(assets, thumbnails, uncertain);

        assertThatThrownBy(() -> uncertainWorkflow.backfillOne(photo.getId())).isInstanceOf(TransactionSystemException.class);

        String thumbnail = ImageThumbnailService.protectedThumbnailKeyFor(photo.getStorageKey());
        assertThat(asset(photo.getId()).getThumbnailStorageKey()).isEqualTo(thumbnail);
        assertThat(storage.objects).containsKey(thumbnail);
        assertThat(storage.deleted).isEmpty();
        assertThat(workflow.backfillOne(photo.getId())).isFalse();
        assertThat(storage.puts.get()).isOne();
    }

    @Test
    void concurrentWorkersBothRecognizeOneCommittedDeterministicVariant() throws Exception {
        var photo = image(draft().id()); seed(photo.getStorageKey());
        CyclicBarrier uploaded = new CyclicBarrier(2);
        storage.afterPut = () -> await(uploaded);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = workers.submit(() -> workflow.backfillOne(photo.getId()));
            Future<Boolean> second = workers.submit(() -> workflow.backfillOne(photo.getId()));
            assertThat(first.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(20, TimeUnit.SECONDS)).isTrue();
        }

        String thumbnail = ImageThumbnailService.protectedThumbnailKeyFor(photo.getStorageKey());
        assertThat(asset(photo.getId()).getThumbnailStorageKey()).isEqualTo(thumbnail);
        assertThat(storage.objects).containsKey(thumbnail);
        assertThat(storage.deleted).isEmpty();
    }

    @Test
    void deletionWhileUploadIsInFlightFencesCasAndFinalSweepRemovesTheSourceAndVariant() throws Exception {
        var listing = draft(); var photo = image(listing.id()); seed(photo.getStorageKey());
        CountDownLatch uploaded = new CountDownLatch(1), finishUpload = new CountDownLatch(1);
        storage.afterPut = () -> { uploaded.countDown(); await(finishUpload); };
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<Boolean> result = worker.submit(() -> workflow.backfillOne(photo.getId()));
            assertThat(uploaded.await(10, TimeUnit.SECONDS)).isTrue();
            tx(() -> { listings.find(listing.id(), seller, true); lifecycle.deleteListingMedia(listing.id()); return null; });
            assertThat(asset(photo.getId()).getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);

            deletion.delete(photo.getId());
            assertThat(storage.deleted).isEmpty(); // Grace remains active while the producer owns its snapshot.
            finishUpload.countDown();
            assertThat(result.get(20, TimeUnit.SECONDS)).isFalse();
        } finally { finishUpload.countDown(); }

        assertThat(asset(photo.getId()).getThumbnailStorageKey()).isNull();
        assertThat(storage.objects).containsOnlyKeys(photo.getStorageKey());
        expireProducerGrace(photo.getId());
        deletion.delete(photo.getId());
        assertThat(assets.findById(photo.getId())).isEmpty();
        assertThat(storage.objects).isEmpty();
    }

    @Test
    void sourceChangeBetweenUploadAndCasCannotAttachOldAttemptThumbnail() throws Exception {
        var photo = image(draft().id()); seed(photo.getStorageKey());
        String replacementSource = "protected/private-verified/media/" + photo.getId() + "/attempts/" + UUID.randomUUID() + "/source.jpg";
        seed(replacementSource);
        storage.afterPut = () -> tx(() -> {
            var current = assets.findById(photo.getId()).orElseThrow();
            current.setStorageKey(replacementSource);
            assets.saveAndFlush(current);
            return null;
        });

        assertThat(workflow.backfillOne(photo.getId())).isFalse();

        assertThat(asset(photo.getId()).getStorageKey()).isEqualTo(replacementSource);
        assertThat(asset(photo.getId()).getThumbnailStorageKey()).isNull();
        assertThat(storage.objects).containsOnlyKeys(photo.getStorageKey(), replacementSource);
        assertThat(storage.deleted).containsExactly(ImageThumbnailService.protectedThumbnailKeyFor(photo.getStorageKey()));
    }

    @Test
    void deletionRecoversUploadedButUncommittedVariantAndRetriesStorageFailureDurably() throws Exception {
        var listing = draft(); var photo = image(listing.id()); seed(photo.getStorageKey());
        installDeferredCommitFault();
        assertThatThrownBy(() -> workflow.backfillOne(photo.getId())).isInstanceOf(RuntimeException.class);
        tx(() -> { listings.find(listing.id(), seller, true); lifecycle.deleteListingMedia(listing.id()); return null; });
        expireProducerGrace(photo.getId());
        storage.failNextDelete.set(true);

        deletion.delete(photo.getId());
        assertThat(asset(photo.getId()).getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);

        deletion.delete(photo.getId());
        assertThat(assets.findById(photo.getId())).isEmpty();
        assertThat(storage.objects).isEmpty();
    }

    @Test
    void physicalDeletionRechecksRealReportReferenceBeforeAnyStorageIo() {
        var listing = draft(); var photo = image(listing.id()); seed(photo.getStorageKey());
        tx(() -> { listings.find(listing.id(), seller, true); lifecycle.deleteListingMedia(listing.id()); return null; });
        expireProducerGrace(photo.getId());
        retainInReport(listing.id(), photo.getId()); // Simulate a legacy reference written after deletion intent.

        deletion.delete(photo.getId());

        assertThat(asset(photo.getId()).getStatus()).isEqualTo(MediaStatus.DELETION_PENDING);
        assertThat(storage.objects).containsOnlyKeys(photo.getStorageKey());
        assertThat(storage.deleted).isEmpty();
    }

    private void seed(String key) { storage.objects.put(key, new byte[] {10, 20, 30}); }

    private void installDeferredCommitFault() {
        jdbc.getJdbcTemplate().execute("""
                create table test_thumbnail_fault(enabled boolean not null);
                insert into test_thumbnail_fault values(true);
                create function test_fail_thumbnail_commit() returns trigger language plpgsql as $$
                begin
                  if new.thumbnail_storage_key is not null and old.thumbnail_storage_key is null
                     and exists(select 1 from test_thumbnail_fault where enabled) then
                    insert into test_commit_guard values(999);
                  end if;
                  return new;
                end $$;
                create trigger test_thumbnail_commit_failure after update of thumbnail_storage_key
                  on tbl_media_asset for each row execute function test_fail_thumbnail_commit();
                """);
    }

    private void expireProducerGrace(UUID assetId) {
        tx(() -> {
            EntityManagerFactoryUtils.getTransactionalEntityManager(emf)
                    .createQuery("update MediaAsset m set m.physicalDeletionNotBefore=:deadline where m.id=:id")
                    .setParameter("deadline", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                    .setParameter("id", assetId).executeUpdate();
            return null;
        });
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("Producer barrier timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static void await(CyclicBarrier barrier) {
        try { barrier.await(10, TimeUnit.SECONDS); }
        catch (Exception failure) { throw new AssertionError("Producer barrier failed", failure); }
    }

    /** A bounded in-process object store; no AWS credentials, ports or external writes. */
    static final class MemoryStorage implements StorageClient {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final Map<String, String> cacheControl = new ConcurrentHashMap<>();
        final List<String> deleted = new CopyOnWriteArrayList<>();
        final AtomicInteger puts = new AtomicInteger();
        final AtomicBoolean failNextDelete = new AtomicBoolean();
        volatile Runnable afterPut = () -> {};
        @Override public void putFile(Path local, String key, String mime, String cache) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(key).startsWith("protected/private-verified/");
            try { objects.put(key, Files.readAllBytes(local)); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
            cacheControl.put(key, cache); puts.incrementAndGet(); afterPut.run();
        }
        @Override public void downloadToFile(String key, Path target) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            try { Files.write(target, Objects.requireNonNull(objects.get(key), "Missing fake source")); }
            catch (IOException failure) { throw new UncheckedIOException(failure); }
        }
        @Override public void deleteObject(String key) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (failNextDelete.compareAndSet(true, false)) throw new IllegalStateException("Injected storage failure");
            deleted.add(key); objects.remove(key);
        }
        @Override public void deleteFolder(String prefix) {
            String directory = prefix.endsWith("/") ? prefix : prefix + "/";
            for (String key : List.copyOf(objects.keySet())) if (key.startsWith(directory)) deleteObject(key);
        }
        @Override public String publicUrl(String key) { throw new AssertionError("Private image reached public delivery"); }
        @Override public String createPresignedPutUrl(String key, String mime, long size) { throw new UnsupportedOperationException(); }
        @Override public StorageAccessUrl createPresignedGetUrl(String key) { throw new UnsupportedOperationException(); }
        @Override public Optional<StorageObjectMetadata> getObjectMetadata(String key) { throw new UnsupportedOperationException(); }
        @Override public void copyUploadToImmutable(String source, String target, String etag) { throw new UnsupportedOperationException(); }
        @Override public void promoteVerifiedObject(String source, String target, String mime, String cache, String etag) { throw new AssertionError("Private promotion"); }
        @Override public void putBytes(byte[] bytes, String key, String mime, String cache) { throw new UnsupportedOperationException(); }
        @Override public InputStream getObjectStream(String key) { return new ByteArrayInputStream(objects.get(key)); }
        @Override public void deleteFolderExcept(String prefix, String retained) { throw new UnsupportedOperationException(); }
        @Override public void deleteFolderExceptPrefix(String prefix, String retained) { throw new UnsupportedOperationException(); }
        @Override public void invalidatePublicAsset(UUID id) { throw new AssertionError("Private image reached public CDN invalidation"); }
    }
}
