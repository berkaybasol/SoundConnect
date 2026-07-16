package com.berkayb.soundconnect.modules.media.verification;

import com.berkayb.soundconnect.modules.media.abuse.MediaUploadAbuseGuard;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.image.MediaImageVariantProperties;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.service.MediaObjectPromotionCoordinator;
import com.berkayb.soundconnect.modules.media.service.MediaUploadFailureHandler;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectMetadata;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaUploadVerificationCoordinatorTest {

	@Test
	void expiredNodeACannotOverwriteNodeBPrivateSnapshotOrFinalState() throws Exception {
		UUID assetId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		String mutableKey = "protected/media/" + assetId + "/source.png";
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.UPLOADING)
				.visibility(MediaVisibility.PRIVATE)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.mimeType("image/png")
				.size(8L)
				.storageKey(mutableKey)
				.build();

		MediaAssetRepository repository = mock(MediaAssetRepository.class);
		StorageClient storage = mock(StorageClient.class);
		MediaPolicy policy = mock(MediaPolicy.class);
		PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
		when(transactions.getTransaction(any(TransactionDefinition.class)))
				.thenAnswer(ignored -> new SimpleTransactionStatus());
		when(repository.findByIdForUpdate(assetId)).thenReturn(Optional.of(asset));
		when(repository.findById(assetId)).thenReturn(Optional.of(asset));
		when(storage.getObjectMetadata(any())).thenAnswer(invocation -> {
			String key = invocation.getArgument(0);
			return Optional.of(new StorageObjectMetadata(
					8L, "image/png", key.equals(mutableKey) ? "mutable-etag" : "snapshot-etag"));
		});
		when(storage.getObjectStream(any())).thenAnswer(ignored -> new ByteArrayInputStream(
				new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}));

		CountDownLatch nodeASnapshotStarted = new CountDownLatch(1);
		CountDownLatch releaseNodeA = new CountDownLatch(1);
		List<String> snapshotDestinations = new CopyOnWriteArrayList<>();
		doAnswer(invocation -> {
			String destination = invocation.getArgument(1);
			snapshotDestinations.add(destination);
			if (snapshotDestinations.size() == 1) {
				nodeASnapshotStarted.countDown();
				assertThat(releaseNodeA.await(5, TimeUnit.SECONDS)).isTrue();
			}
			return null;
		}).when(storage).copyUploadToImmutable(any(), any(), any());

		MediaUploadVerificationProperties properties = new MediaUploadVerificationProperties();
		TaskExecutor asyncNodeA = task -> {
			Thread thread = new Thread(task, "verification-node-a-test");
			thread.setDaemon(true);
			thread.start();
		};
		TaskExecutor directNodeB = Runnable::run;
		MediaUploadVerificationCoordinator nodeA = coordinator(
				repository, storage, policy, transactions, properties, asyncNodeA);
		MediaUploadVerificationCoordinator nodeB = coordinator(
				repository, storage, policy, transactions, properties, directNodeB);

		CompletableFuture<MediaAsset> resultA = CompletableFuture.supplyAsync(
				() -> nodeA.complete(assetId, MediaOwnerType.USER, ownerId));
		assertThat(nodeASnapshotStarted.await(5, TimeUnit.SECONDS)).isTrue();
		UUID tokenA = asset.getUploadVerificationAttemptToken();
		asset.setUploadVerificationLeaseExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));

		MediaAsset resultB = nodeB.complete(assetId, MediaOwnerType.USER, ownerId);
		UUID tokenB = asset.getUploadVerificationAttemptToken();
		String committedKeyB = asset.getStorageKey();
		releaseNodeA.countDown();
		MediaAsset lateResultA = resultA.get(5, TimeUnit.SECONDS);

		assertThat(tokenA).isNotEqualTo(tokenB);
		assertThat(snapshotDestinations).hasSize(2);
		assertThat(snapshotDestinations.get(0)).contains("/attempts/" + tokenA + "/");
		assertThat(snapshotDestinations.get(1)).contains("/attempts/" + tokenB + "/");
		assertThat(snapshotDestinations.get(0)).isNotEqualTo(snapshotDestinations.get(1));
		assertThat(resultB.getStatus()).isEqualTo(MediaStatus.READY);
		assertThat(committedKeyB).isEqualTo(snapshotDestinations.get(1));
		assertThat(lateResultA.getStorageKey()).isEqualTo(committedKeyB);
		assertThat(asset.getUploadVerificationAttemptToken()).isEqualTo(tokenB);
		verify(storage, never()).promoteVerifiedObject(any(), any(), any(), any(), any());
	}

	@Test
	void liveCrossNodeLeaseReturnsNotReadyBeforeLocalExecutorAdmission() {
		UUID assetId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.VERIFYING)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.uploadVerificationAttemptToken(UUID.randomUUID())
				.uploadVerificationLeaseExpiresAt(
						LocalDateTime.now(ZoneOffset.UTC).plusMinutes(2))
				.build();
		MediaAssetRepository repository = mock(MediaAssetRepository.class);
		when(repository.findById(assetId)).thenReturn(Optional.of(asset));
		AtomicBoolean admitted = new AtomicBoolean(false);
		TaskExecutor executor = task -> admitted.set(true);
		MediaUploadVerificationCoordinator coordinator = coordinator(
				repository,
				mock(StorageClient.class),
				mock(MediaPolicy.class),
				mock(PlatformTransactionManager.class),
				new MediaUploadVerificationProperties(),
				executor
		);

		assertThatThrownBy(() -> coordinator.complete(
				assetId, MediaOwnerType.USER, ownerId))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.MEDIA_ASSET_NOT_READY));

		assertThat(admitted).isFalse();
	}

	@Test
	void claimBetweenPreflightAndRejectedAdmissionStillReturnsNotReady() {
		UUID assetId = UUID.randomUUID();
		UUID ownerId = UUID.randomUUID();
		MediaAsset asset = MediaAsset.builder()
				.id(assetId)
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.UPLOADING)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.build();
		MediaAssetRepository repository = mock(MediaAssetRepository.class);
		when(repository.findById(assetId)).thenReturn(Optional.of(asset));
		TaskExecutor saturated = task -> {
			asset.setStatus(MediaStatus.VERIFYING);
			asset.setUploadVerificationAttemptToken(UUID.randomUUID());
			asset.setUploadVerificationLeaseExpiresAt(
					LocalDateTime.now(ZoneOffset.UTC).plusMinutes(2));
			throw new TaskRejectedException("saturated");
		};
		MediaUploadVerificationCoordinator coordinator = coordinator(
				repository,
				mock(StorageClient.class),
				mock(MediaPolicy.class),
				mock(PlatformTransactionManager.class),
				new MediaUploadVerificationProperties(),
				saturated
		);

		assertThatThrownBy(() -> coordinator.complete(
				assetId, MediaOwnerType.USER, ownerId))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.MEDIA_ASSET_NOT_READY));
	}

	private static MediaUploadVerificationCoordinator coordinator(
			MediaAssetRepository repository,
			StorageClient storage,
			MediaPolicy policy,
			PlatformTransactionManager transactions,
			MediaUploadVerificationProperties properties,
			TaskExecutor executor
	) {
		return new MediaUploadVerificationCoordinator(
				repository,
				storage,
				policy,
				new MediaObjectPromotionCoordinator(storage, new MediaImageVariantProperties()),
				mock(MediaUploadFailureHandler.class),
				mock(MediaUploadAbuseGuard.class),
				mock(ApplicationEventPublisher.class),
				executor,
				properties,
				transactions
		);
	}
}
