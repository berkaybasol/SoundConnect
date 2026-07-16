package com.berkayb.soundconnect.modules.media.storage;

import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Logical storage-key conventions for public, protected and quarantined media. */
public final class StorageObjectKeys {

	private static final String PROTECTED_PREFIX = "protected/";
	private static final String QUARANTINE_PREFIX = "quarantine/";
	private static final String VERIFIED_PREFIX = "verified/";
	private static final String PROTECTED_VERIFIED_PREFIX = PROTECTED_PREFIX + "private-verified/";
	private static final String ATTEMPTS_SEGMENT = "/attempts/";

	private StorageObjectKeys() {
	}

	public static String protectedKey(String objectKey) {
		validate(objectKey);
		if (isQuarantined(objectKey) || isVerified(objectKey)) {
			throw new IllegalArgumentException(
					"A quarantine or verified key cannot be converted to a protected key");
		}
		return isProtected(objectKey) ? objectKey : PROTECTED_PREFIX + objectKey;
	}

	public static String quarantineKey(String objectKey) {
		validate(objectKey);
		if (isProtected(objectKey) || isVerified(objectKey)) {
			throw new IllegalArgumentException(
					"A protected or verified key cannot be converted to a quarantine key");
		}
		return isQuarantined(objectKey) ? objectKey : QUARANTINE_PREFIX + objectKey;
	}

	public static boolean isProtected(String objectKey) {
		return StringUtils.hasText(objectKey) && objectKey.startsWith(PROTECTED_PREFIX);
	}

	public static boolean isQuarantined(String objectKey) {
		return StringUtils.hasText(objectKey) && objectKey.startsWith(QUARANTINE_PREFIX);
	}

	public static boolean isVerified(String objectKey) {
		return StringUtils.hasText(objectKey)
				&& (objectKey.startsWith(VERIFIED_PREFIX)
				|| objectKey.startsWith(PROTECTED_VERIFIED_PREFIX));
	}

	public static boolean isPrivateVerified(String objectKey) {
		return StringUtils.hasText(objectKey)
				&& objectKey.startsWith(PROTECTED_VERIFIED_PREFIX);
	}

	public static String privateVerifiedPrefix() {
		return PROTECTED_VERIFIED_PREFIX;
	}

	/** Reconstructs the client-writable source of a legacy or attempt-scoped snapshot. */
	public static String mutableUploadKeyForPrivateVerified(String privateVerifiedKey) {
		validate(privateVerifiedKey);
		if (!isPrivateVerified(privateVerifiedKey)) {
			throw new IllegalArgumentException("Expected a private-verified object key");
		}
		String suffix = privateVerifiedKey.substring(PROTECTED_VERIFIED_PREFIX.length());
		String mutableSuffix = removeAttemptScope(suffix);
		validate(mutableSuffix);
		return PROTECTED_PREFIX + mutableSuffix;
	}

	public static boolean isPrivateOrigin(String objectKey) {
		return isProtected(objectKey) || isQuarantined(objectKey) || isVerified(objectKey);
	}

	public static boolean isClientWritableUpload(String objectKey) {
		return isQuarantined(objectKey) || (isProtected(objectKey) && !isVerified(objectKey));
	}

	public static boolean hasClientWritablePredecessor(String objectKey) {
		if (!StringUtils.hasText(objectKey) || isClientWritableUpload(objectKey)) return false;
		return isPrivateVerified(objectKey)
				|| objectKey.startsWith(VERIFIED_PREFIX)
				|| !isPrivateOrigin(objectKey);
	}

	/**
	 * Produces an attempt-scoped server-only destination. Two cross-node workers
	 * can never overwrite one another's validated private/video snapshot.
	 */
	public static String immutableKeyForUpload(String uploadKey, UUID attemptToken) {
		Objects.requireNonNull(attemptToken, "attemptToken");
		String legacyBase = immutableKeyForUpload(uploadKey);
		return insertAttemptScope(legacyBase, attemptToken);
	}

	/** Legacy deterministic form retained only for rolling-upgrade cleanup. */
	public static String immutableKeyForUpload(String uploadKey) {
		validate(uploadKey);
		if (isVerified(uploadKey)) {
			throw new IllegalArgumentException("Upload key is already immutable");
		}
		if (isQuarantined(uploadKey)) {
			return VERIFIED_PREFIX + publicKeyForQuarantine(uploadKey);
		}
		if (isProtected(uploadKey)) {
			return PROTECTED_VERIFIED_PREFIX + physicalKey(uploadKey);
		}
		throw new IllegalArgumentException("Immutable copies require a private upload key");
	}

	/** Accepts both the rolling legacy form and the new attempt-scoped form. */
	public static boolean isImmutableSnapshotForUpload(String uploadKey, String immutableKey) {
		try {
			validate(uploadKey);
			validate(immutableKey);
			if (isQuarantined(uploadKey) && immutableKey.startsWith(VERIFIED_PREFIX)) {
				return mutableUploadKeyForVerified(immutableKey).equals(uploadKey);
			}
			if (isProtected(uploadKey) && isPrivateVerified(immutableKey)) {
				return mutableUploadKeyForPrivateVerified(immutableKey).equals(uploadKey);
			}
			return false;
		} catch (IllegalArgumentException invalidKey) {
			return false;
		}
	}

	public static String publicKeyForVerified(String verifiedKey) {
		validate(verifiedKey);
		if (!verifiedKey.startsWith(VERIFIED_PREFIX) || isProtected(verifiedKey)) {
			throw new IllegalArgumentException("Expected a public-intent verified key");
		}
		String publicKey = verifiedKey.substring(VERIFIED_PREFIX.length());
		validate(publicKey);
		validateAttemptScopeIfPresent(publicKey);
		if (isPrivateOrigin(publicKey)) {
			throw new IllegalArgumentException("Verified key contains a reserved storage prefix");
		}
		return publicKey;
	}

	/** Maps an attempt-scoped or legacy public-intent snapshot to its mutable source. */
	public static String mutableUploadKeyForVerified(String verifiedKey) {
		String publicKey = publicKeyForVerified(verifiedKey);
		return quarantineKey(removeAttemptScope(publicKey));
	}

	public static String verifiedKeyForPublicObject(String publicObjectKey) {
		validate(publicObjectKey);
		if (isPrivateOrigin(publicObjectKey)) {
			throw new IllegalArgumentException("Expected a public object key");
		}
		return VERIFIED_PREFIX + publicObjectKey;
	}

	public static String publicKeyForQuarantine(String quarantineKey) {
		validate(quarantineKey);
		if (!isQuarantined(quarantineKey)) {
			throw new IllegalArgumentException("Expected a quarantine object key");
		}
		String publicKey = quarantineKey.substring(QUARANTINE_PREFIX.length());
		validate(publicKey);
		if (isPrivateOrigin(publicKey)) {
			throw new IllegalArgumentException("Quarantine key contains a reserved storage prefix");
		}
		return publicKey;
	}

	public static List<String> abandonedUploadCleanupKeys(String storageKey) {
		return abandonedUploadCleanupKeys(storageKey, null);
	}

	/**
	 * Returns every known crash companion. The token is needed only when the row
	 * currently points at the mutable or stable public key; scoped immutable keys
	 * encode their own token. Legacy companions remain included for safe rollout.
	 */
	public static List<String> abandonedUploadCleanupKeys(
			String storageKey,
			UUID attemptToken
	) {
		validate(storageKey);
		LinkedHashSet<String> keys = new LinkedHashSet<>();
		keys.add(storageKey);

		if (isClientWritableUpload(storageKey)) {
			String legacyImmutable = immutableKeyForUpload(storageKey);
			keys.add(legacyImmutable);
			String scopedImmutable = null;
			if (attemptToken != null) {
				scopedImmutable = immutableKeyForUpload(storageKey, attemptToken);
				keys.add(scopedImmutable);
			}
			if (isQuarantined(storageKey)) {
				keys.add(publicKeyForVerified(legacyImmutable));
				if (scopedImmutable != null) {
					keys.add(publicKeyForVerified(scopedImmutable));
				}
			}
		} else if (isPrivateVerified(storageKey)) {
			keys.add(mutableUploadKeyForPrivateVerified(storageKey));
		} else if (storageKey.startsWith(VERIFIED_PREFIX)) {
			String publicKey = publicKeyForVerified(storageKey);
			keys.add(publicKey);
			keys.add(mutableUploadKeyForVerified(storageKey));
		} else if (!isPrivateOrigin(storageKey)) {
			String mutableKey = quarantineKey(removeAttemptScope(storageKey));
			if (hasAttemptScope(storageKey)) {
				keys.add(VERIFIED_PREFIX + storageKey);
			} else {
				keys.add(immutableKeyForUpload(mutableKey));
				if (attemptToken != null) {
					keys.add(immutableKeyForUpload(mutableKey, attemptToken));
				}
			}
			keys.add(mutableKey);
		}

		return List.copyOf(keys);
	}

	/**
	 * Asset-scoped attempt directories. A delayed recovery sweep may delete every
	 * object under these prefixes except the exact key still referenced by the DB.
	 */
	public static List<String> attemptPrefixesFor(String storageKey) {
		validate(storageKey);
		LinkedHashSet<String> prefixes = new LinkedHashSet<>();
		if (isPrivateVerified(storageKey)) {
			String mutable = mutableUploadKeyForPrivateVerified(storageKey);
			prefixes.add(immutableAttemptPrefixForUpload(mutable));
		} else if (isProtected(storageKey) && !isVerified(storageKey)) {
			prefixes.add(immutableAttemptPrefixForUpload(storageKey));
		} else {
			String mutable;
			if (storageKey.startsWith(VERIFIED_PREFIX)) {
				mutable = mutableUploadKeyForVerified(storageKey);
			} else if (isQuarantined(storageKey)) {
				mutable = storageKey;
			} else if (!isPrivateOrigin(storageKey)) {
				mutable = quarantineKey(removeAttemptScope(storageKey));
			} else {
				return List.of();
			}
			prefixes.add(immutableAttemptPrefixForUpload(mutable));
			prefixes.add(publicAttemptPrefixForUpload(mutable));
		}
		return List.copyOf(prefixes);
	}

	public static String immutableAttemptPrefixForUpload(String uploadKey) {
		String legacy = immutableKeyForUpload(uploadKey);
		return directoryOf(legacy) + "/attempts";
	}

	public static String publicAttemptPrefixForUpload(String quarantineUploadKey) {
		String publicKey = publicKeyForQuarantine(quarantineUploadKey);
		return directoryOf(publicKey) + "/attempts";
	}

	/** Winner attempt directory to retain inside one private/public family prefix. */
	public static String retainedAttemptSubtreeForPrefix(
			String currentStorageKey,
			String attemptFamilyPrefix
	) {
		validate(currentStorageKey);
		if (!StringUtils.hasText(attemptFamilyPrefix)
				|| !attemptFamilyPrefix.endsWith("/attempts")) {
			throw new IllegalArgumentException("Expected an attempt-family prefix");
		}
		LinkedHashSet<String> candidates = new LinkedHashSet<>();
		candidates.add(currentStorageKey);
		if (!isPrivateOrigin(currentStorageKey)) {
			candidates.add(verifiedKeyForPublicObject(currentStorageKey));
		} else if (currentStorageKey.startsWith(VERIFIED_PREFIX)) {
			candidates.add(publicKeyForVerified(currentStorageKey));
		}
		return candidates.stream()
				.filter(StorageObjectKeys::hasAttemptScope)
				.filter(candidate -> candidate.startsWith(attemptFamilyPrefix))
				.map(StorageObjectKeys::directoryOf)
				.findFirst()
				.orElse(null);
	}

	public static String physicalKey(String objectKey) {
		validate(objectKey);
		return isProtected(objectKey)
				? objectKey.substring(PROTECTED_PREFIX.length())
				: objectKey;
	}

	private static String insertAttemptScope(String objectKey, UUID attemptToken) {
		int lastSlash = objectKey.lastIndexOf('/');
		if (lastSlash <= 0 || lastSlash == objectKey.length() - 1) {
			throw new IllegalArgumentException("Immutable media key must contain a filename");
		}
		return objectKey.substring(0, lastSlash)
				+ ATTEMPTS_SEGMENT + attemptToken
				+ objectKey.substring(lastSlash);
	}

	private static boolean hasAttemptScope(String objectKey) {
		return objectKey.lastIndexOf(ATTEMPTS_SEGMENT) >= 0;
	}

	private static void validateAttemptScopeIfPresent(String objectKey) {
		if (hasAttemptScope(objectKey)) removeAttemptScope(objectKey);
	}

	private static String directoryOf(String objectKey) {
		int lastSlash = objectKey.lastIndexOf('/');
		if (lastSlash <= 0) {
			throw new IllegalArgumentException("Media key must contain an asset directory");
		}
		return objectKey.substring(0, lastSlash);
	}

	private static String removeAttemptScope(String objectKey) {
		int marker = objectKey.lastIndexOf(ATTEMPTS_SEGMENT);
		if (marker < 0) return objectKey;

		int tokenStart = marker + ATTEMPTS_SEGMENT.length();
		int tokenEnd = objectKey.indexOf('/', tokenStart);
		if (tokenEnd < 0 || tokenEnd == objectKey.length() - 1) {
			throw new IllegalArgumentException("Attempt-scoped immutable key is incomplete");
		}
		String token = objectKey.substring(tokenStart, tokenEnd);
		try {
			UUID.fromString(token);
		} catch (IllegalArgumentException invalidToken) {
			throw new IllegalArgumentException("Attempt-scoped immutable key has an invalid token");
		}
		if (objectKey.indexOf('/', tokenEnd + 1) >= 0) {
			throw new IllegalArgumentException("Attempt-scoped immutable key has an invalid filename");
		}
		return objectKey.substring(0, marker) + objectKey.substring(tokenEnd);
	}

	private static void validate(String objectKey) {
		if (!StringUtils.hasText(objectKey)) {
			throw new IllegalArgumentException("objectKey must not be blank");
		}
		if (!objectKey.equals(objectKey.trim())
				|| objectKey.startsWith("/")
				|| objectKey.contains("\\")
				|| objectKey.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("objectKey contains an unsafe path form");
		}
		for (String segment : objectKey.split("/", -1)) {
			if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
				throw new IllegalArgumentException("objectKey contains an unsafe path segment");
			}
		}
	}
}
