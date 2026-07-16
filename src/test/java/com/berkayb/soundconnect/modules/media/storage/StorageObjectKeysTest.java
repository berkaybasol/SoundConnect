package com.berkayb.soundconnect.modules.media.storage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageObjectKeysTest {

	@Test
	void protectedMarkerIsIdempotentAndNotPartOfPhysicalKey() {
		String physicalKey = "media/id/source.png";
		String protectedKey = StorageObjectKeys.protectedKey(physicalKey);

		assertThat(protectedKey).isEqualTo("protected/" + physicalKey);
		assertThat(StorageObjectKeys.protectedKey(protectedKey)).isEqualTo(protectedKey);
		assertThat(StorageObjectKeys.isProtected(protectedKey)).isTrue();
		assertThat(StorageObjectKeys.physicalKey(protectedKey)).isEqualTo(physicalKey);
	}

	@Test
	void quarantineMarkerRoutesPrivateAndMapsToOneDeterministicPublicKey() {
		String publicKey = "media/id/source.png";
		String quarantineKey = StorageObjectKeys.quarantineKey(publicKey);

		assertThat(quarantineKey).isEqualTo("quarantine/" + publicKey);
		assertThat(StorageObjectKeys.quarantineKey(quarantineKey)).isEqualTo(quarantineKey);
		assertThat(StorageObjectKeys.isQuarantined(quarantineKey)).isTrue();
		assertThat(StorageObjectKeys.isPrivateOrigin(quarantineKey)).isTrue();
		assertThat(StorageObjectKeys.physicalKey(quarantineKey)).isEqualTo(quarantineKey);
		assertThat(StorageObjectKeys.publicKeyForQuarantine(quarantineKey)).isEqualTo(publicKey);
	}

	@Test
	void reservedKeyFamiliesCannotBeCrossWrapped() {
		assertThatThrownBy(() -> StorageObjectKeys.quarantineKey("protected/media/id/source.png"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> StorageObjectKeys.protectedKey("quarantine/media/id/source.png"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> StorageObjectKeys.publicKeyForQuarantine("media/id/source.png"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void mutableUploadsMapToDistinctServerOnlyImmutableKeys() {
		String quarantine = "quarantine/media/public-id/source.png";
		String protectedUpload = "protected/media/private-id/source.mp3";

		String publicIntentSnapshot = StorageObjectKeys.immutableKeyForUpload(quarantine);
		String privateSnapshot = StorageObjectKeys.immutableKeyForUpload(protectedUpload);

		assertThat(publicIntentSnapshot).isEqualTo("verified/media/public-id/source.png");
		assertThat(privateSnapshot).isEqualTo("protected/private-verified/media/private-id/source.mp3");
		assertThat(StorageObjectKeys.isVerified(publicIntentSnapshot)).isTrue();
		assertThat(StorageObjectKeys.isVerified(privateSnapshot)).isTrue();
		assertThat(StorageObjectKeys.isPrivateOrigin(publicIntentSnapshot)).isTrue();
		assertThat(StorageObjectKeys.isPrivateOrigin(privateSnapshot)).isTrue();
		assertThat(StorageObjectKeys.publicKeyForVerified(publicIntentSnapshot))
				.isEqualTo("media/public-id/source.png");
		assertThatThrownBy(() -> StorageObjectKeys.publicKeyForVerified(privateSnapshot))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void expiredAttemptCannotOverwriteNewOwnersImmutableSnapshot() {
		String mutable = "quarantine/media/public-id/source.png";
		UUID expiredAttempt = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		UUID newAttempt = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

		String staleDestination = StorageObjectKeys.immutableKeyForUpload(mutable, expiredAttempt);
		String liveDestination = StorageObjectKeys.immutableKeyForUpload(mutable, newAttempt);

		assertThat(staleDestination).isNotEqualTo(liveDestination);
		assertThat(staleDestination).contains("/attempts/" + expiredAttempt + "/");
		assertThat(liveDestination).contains("/attempts/" + newAttempt + "/");
		String stalePublic = StorageObjectKeys.publicKeyForVerified(staleDestination);
		String livePublic = StorageObjectKeys.publicKeyForVerified(liveDestination);
		assertThat(stalePublic).isNotEqualTo(livePublic);
		assertThat(stalePublic).contains("/attempts/" + expiredAttempt + "/");
		assertThat(livePublic).contains("/attempts/" + newAttempt + "/");
		assertThat(StorageObjectKeys.mutableUploadKeyForVerified(staleDestination)).isEqualTo(mutable);
		assertThat(StorageObjectKeys.mutableUploadKeyForVerified(liveDestination)).isEqualTo(mutable);
		assertThat(StorageObjectKeys.isImmutableSnapshotForUpload(mutable, staleDestination)).isTrue();
		assertThat(StorageObjectKeys.isImmutableSnapshotForUpload(mutable, liveDestination)).isTrue();
	}

	@Test
	void cleanupUsesPersistedAttemptTokenToCoverScopedAndLegacyCompanions() {
		String mutable = "quarantine/media/public-id/source.png";
		UUID attempt = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

		assertThat(StorageObjectKeys.abandonedUploadCleanupKeys(mutable, attempt))
				.containsExactly(
						mutable,
						"verified/media/public-id/source.png",
						"verified/media/public-id/attempts/" + attempt + "/source.png",
						"media/public-id/source.png",
						"media/public-id/attempts/" + attempt + "/source.png");
	}

	@Test
	void abandonedPublicUploadCleanupCoversEveryCrashStage() {
		String quarantine = "quarantine/media/public-id/source.png";

		assertThat(StorageObjectKeys.abandonedUploadCleanupKeys(quarantine))
				.containsExactly(
						quarantine,
						"verified/media/public-id/source.png",
						"media/public-id/source.png");
		assertThat(StorageObjectKeys.isClientWritableUpload(quarantine)).isTrue();
		assertThat(StorageObjectKeys.isClientWritableUpload(
				"verified/media/public-id/source.png")).isFalse();
	}

	@Test
	void committedPublicKeysReverseMapToEveryPrivateCrashCompanion() {
		String publicKey = "media/public-id/source.png";
		String verifiedKey = "verified/" + publicKey;

		assertThat(StorageObjectKeys.abandonedUploadCleanupKeys(publicKey))
				.containsExactly(publicKey, verifiedKey, "quarantine/" + publicKey);
		assertThat(StorageObjectKeys.abandonedUploadCleanupKeys(verifiedKey))
				.containsExactly(verifiedKey, publicKey, "quarantine/" + publicKey);
		assertThat(StorageObjectKeys.hasClientWritablePredecessor(publicKey)).isTrue();
		assertThat(StorageObjectKeys.hasClientWritablePredecessor(verifiedKey)).isTrue();
	}

	@Test
	void abandonedProtectedUploadTargetsOnlyItsDeterministicObjects() {
		String mutable = "protected/media/private-id/source.mp3";
		String livePrivateKey = "protected/private-verified/media/private-id/source.mp3";

		assertThat(StorageObjectKeys.abandonedUploadCleanupKeys(mutable))
				.containsExactly(mutable, livePrivateKey);
		assertThat(StorageObjectKeys.abandonedUploadCleanupKeys(livePrivateKey))
				.containsExactly(livePrivateKey, mutable);
		assertThat(StorageObjectKeys.isPrivateVerified(livePrivateKey)).isTrue();
		assertThat(StorageObjectKeys.hasClientWritablePredecessor(livePrivateKey)).isTrue();
		assertThat(StorageObjectKeys.mutableUploadKeyForPrivateVerified(livePrivateKey))
				.isEqualTo(mutable);
		assertThat(StorageObjectKeys.isClientWritableUpload(livePrivateKey)).isFalse();
		assertThatThrownBy(() -> StorageObjectKeys.mutableUploadKeyForPrivateVerified(mutable))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void blankKeysAreRejected() {
		assertThatThrownBy(() -> StorageObjectKeys.protectedKey(" "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> StorageObjectKeys.physicalKey(null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> StorageObjectKeys.quarantineKey(""))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> StorageObjectKeys.quarantineKey("../media/source.png"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> StorageObjectKeys.quarantineKey("media\\source.png"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> StorageObjectKeys.quarantineKey("media//source.png"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> StorageObjectKeys.publicKeyForVerified(
				"verified/media/id/attempts/not-a-uuid/source.png"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> StorageObjectKeys.mutableUploadKeyForPrivateVerified(
				"protected/private-verified/media/id/attempts/not-a-uuid/source.mp3"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
