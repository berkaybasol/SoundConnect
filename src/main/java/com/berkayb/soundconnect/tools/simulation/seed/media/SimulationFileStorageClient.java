package com.berkayb.soundconnect.tools.simulation.seed.media;

import com.berkayb.soundconnect.modules.media.storage.StorageAccessUrl;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectKeys;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectMetadata;
import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Filesystem object store used only by the guarded local simulation runtime.
 *
 * <p>It preserves the storage port's important security semantics: a presigned
 * upload is an opaque, expiring, single-use capability; completion sees
 * authoritative size, MIME and ETag; immutable snapshots are conditional on
 * that ETag; and public promotion crosses from a private namespace into a
 * distinct public namespace. Logical object keys are never accepted as file
 * paths without canonical traversal and symlink checks.</p>
 */
public final class SimulationFileStorageClient
		implements StorageClient, SimulationPresignedUploadSink {

	private static final String PRIVATE_NAMESPACE = "private";
	private static final String PUBLIC_NAMESPACE = "public";
	private static final String METADATA_SUFFIX = ".simulation-meta";
	private static final String METADATA_VERSION = "simulation-object-v1";
	private static final String CAPABILITY_KEY_FILE = ".public-capability-key";
	private static final String CAPABILITY_DOMAIN = "soundconnect-simulation-media-v1\n";
	static final String PUBLIC_ROUTE = "/api/v1/public/simulation-media";
	private static final int TOKEN_BYTES = 32;
	private static final int MAX_OUTSTANDING_UPLOADS = 512;
	private static final int MAX_RESET_PATHS = 50_000;
	private static final long MAX_METADATA_BYTES = 4_096L;
	private static final long DEFAULT_MAX_RESPONSE_BYTES = 8L * 1024L * 1024L;
	private static final int DEFAULT_MAX_PUBLIC_CAPABILITIES = 4_096;
	private static final String DEFAULT_PUBLIC_BASE_URL = "http://10.0.2.2:8080";
	private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

	private final SimulationRuntimeGuard runtimeGuard;
	private final Path reportDirectory;
	private final Path storageRoot;
	private final Path privateRoot;
	private final Path publicRoot;
	private final Clock clock;
	private final Duration uploadTtl;
	private final Duration downloadTtl;
	private final SecureRandom secureRandom;
	private final String publicBaseUrl;
	private final long maxResponseBytes;
	private final int maxPublicCapabilities;
	private final SimulationMode simulationMode;
	private final byte[] publicCapabilityKey;
	private final Map<String, UploadGrant> uploadGrants = new ConcurrentHashMap<>();
	private final Map<String, String> publicCapabilities = new HashMap<>();
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

	public SimulationFileStorageClient(
			SimulationRuntimeGuard runtimeGuard,
			Path reportDirectory,
			Clock clock,
			Duration uploadTtl,
			Duration downloadTtl
	) {
		this(
				runtimeGuard,
				reportDirectory,
				clock,
				uploadTtl,
				downloadTtl,
				DEFAULT_PUBLIC_BASE_URL,
				DEFAULT_MAX_RESPONSE_BYTES,
				DEFAULT_MAX_PUBLIC_CAPABILITIES,
				SimulationMode.FRESH,
				new SecureRandom());
	}

	public SimulationFileStorageClient(
			SimulationRuntimeGuard runtimeGuard,
			Path reportDirectory,
			Clock clock,
			Duration uploadTtl,
			Duration downloadTtl,
			String publicBaseUrl,
			long maxResponseBytes,
			int maxPublicCapabilities,
			SimulationMode simulationMode
	) {
		this(
				runtimeGuard,
				reportDirectory,
				clock,
				uploadTtl,
				downloadTtl,
				publicBaseUrl,
				maxResponseBytes,
				maxPublicCapabilities,
				simulationMode,
				new SecureRandom());
	}

	SimulationFileStorageClient(
			SimulationRuntimeGuard runtimeGuard,
			Path reportDirectory,
			Clock clock,
			Duration uploadTtl,
			Duration downloadTtl,
			String publicBaseUrl,
			long maxResponseBytes,
			int maxPublicCapabilities,
			SimulationMode simulationMode,
			SecureRandom secureRandom
	) {
		this.runtimeGuard = require(runtimeGuard, "runtimeGuard");
		this.clock = require(clock, "clock");
		this.uploadTtl = validTtl(uploadTtl, "uploadTtl");
		this.downloadTtl = validTtl(downloadTtl, "downloadTtl");
		this.secureRandom = require(secureRandom, "secureRandom");
		this.publicBaseUrl = validatePublicBaseUrl(publicBaseUrl);
		if (maxResponseBytes < 1 || maxResponseBytes > 64L * 1024L * 1024L) {
			throw new IllegalArgumentException("maxResponseBytes must be between 1 and 64 MiB");
		}
		if (maxPublicCapabilities < 1 || maxPublicCapabilities > 20_000) {
			throw new IllegalArgumentException(
					"maxPublicCapabilities must be between 1 and 20000");
		}
		this.maxResponseBytes = maxResponseBytes;
		this.maxPublicCapabilities = maxPublicCapabilities;
		this.simulationMode = require(simulationMode, "simulationMode");
		Path reports = require(reportDirectory, "reportDirectory").toAbsolutePath().normalize();
		this.reportDirectory = reports;
		this.storageRoot = reports.resolve("media-storage-v1").normalize();
		if (!storageRoot.startsWith(reports)) {
			throw new IllegalArgumentException("Simulation storage must remain below report-directory");
		}
		this.privateRoot = storageRoot.resolve(PRIVATE_NAMESPACE);
		this.publicRoot = storageRoot.resolve(PUBLIC_NAMESPACE);
		assertRuntime();
		initializeRoots(reports);
		this.publicCapabilityKey = loadOrCreateCapabilityKey();
		indexExistingPublicObjects();
	}

	@Override
	public String createPresignedPutUrl(String objectKey, String mimeType, long sizeBytes) {
		assertRuntime();
		validateLogicalKey(objectKey);
		if (!StorageObjectKeys.isClientWritableUpload(objectKey)) {
			throw new IllegalArgumentException(
					"Presigned simulation uploads require a client-writable private key");
		}
		String normalizedMime = validateContentType(mimeType);
		if (sizeBytes <= 0) {
			throw new IllegalArgumentException("sizeBytes must be positive");
		}
		synchronized (uploadGrants) {
			purgeExpiredGrants();
			if (uploadGrants.size() >= MAX_OUTSTANDING_UPLOADS) {
				throw new IllegalStateException("Simulation upload capability capacity is exhausted");
			}
			String token;
			do {
				token = newToken();
			} while (uploadGrants.putIfAbsent(
					token,
					new UploadGrant(objectKey, normalizedMime, sizeBytes, clock.instant().plus(uploadTtl))) != null);
			return "simulation-upload://local/" + token;
		}
	}

	@Override
	public void upload(String uploadUrl, String contentType, byte[] bytes) {
		assertRuntime();
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("upload bytes must not be empty");
		}
		String token = parseUploadToken(uploadUrl);
		UploadGrant grant = uploadGrants.remove(token);
		if (grant == null) {
			throw new IllegalArgumentException("Simulation upload capability is invalid or already consumed");
		}
		if (!clock.instant().isBefore(grant.expiresAt())) {
			throw new IllegalArgumentException("Simulation upload capability has expired");
		}
		String normalizedMime = validateContentType(contentType);
		if (!grant.contentType().equals(normalizedMime) || grant.sizeBytes() != bytes.length) {
			throw new IllegalArgumentException("Simulation upload does not match its signed metadata");
		}
		writeBytes(grant.objectKey(), bytes, normalizedMime, "no-store");
	}

	@Override
	public StorageAccessUrl createPresignedGetUrl(String objectKey) {
		assertRuntime();
		validateLogicalKey(objectKey);
		if (!StorageObjectKeys.isPrivateOrigin(objectKey)) {
			throw new IllegalArgumentException("Presigned origin reads require a private object key");
		}
		Instant expiresAt = clock.instant().plus(downloadTtl);
		String fingerprint = URL_ENCODER.encodeToString(
				sha256((objectKey + "\n" + expiresAt).getBytes(StandardCharsets.UTF_8)));
		return new StorageAccessUrl("simulation-download://local/" + fingerprint, expiresAt);
	}

	@Override
	public Optional<StorageObjectMetadata> getObjectMetadata(String objectKey) {
		assertRuntime();
		lock.readLock().lock();
		try {
			Path object = resolveObject(objectKey);
			if (!Files.exists(object, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
			assertRegularObject(object);
			StoredMetadata metadata = readMetadata(object);
			return Optional.of(new StorageObjectMetadata(
					metadata.sizeBytes(), metadata.contentType(), metadata.eTag()));
		} finally {
			lock.readLock().unlock();
		}
	}

	@Override
	public void copyUploadToImmutable(
			String uploadObjectKey,
			String immutableObjectKey,
			String expectedETag
	) {
		assertRuntime();
		if (!StorageObjectKeys.isImmutableSnapshotForUpload(uploadObjectKey, immutableObjectKey)
				|| isBlank(expectedETag)) {
			throw new IllegalArgumentException("Invalid immutable upload snapshot request");
		}
		copyConditional(uploadObjectKey, immutableObjectKey, expectedETag, null, null);
	}

	@Override
	public void promoteVerifiedObject(
			String verifiedObjectKey,
			String publicObjectKey,
			String contentType,
			String cacheControl,
			String expectedVerifiedETag
	) {
		assertRuntime();
		String expectedPublicKey = StorageObjectKeys.publicKeyForVerified(verifiedObjectKey);
		if (!expectedPublicKey.equals(publicObjectKey)
				|| StorageObjectKeys.isPrivateOrigin(publicObjectKey)
				|| isBlank(expectedVerifiedETag)) {
			throw new IllegalArgumentException("Invalid verified promotion target");
		}
		copyConditional(
				verifiedObjectKey,
				publicObjectKey,
				expectedVerifiedETag,
				validateContentType(contentType),
				validateHeaderValue(cacheControl, "cacheControl"));
	}

	@Override
	public void putFile(Path local, String key, String contentType, String cacheControl) {
		assertRuntime();
		Path source = require(local, "local").toAbsolutePath().normalize();
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(source)) {
			throw new IllegalArgumentException("Source must be a regular non-symlink file");
		}
		writeFile(
				key,
				source,
				validateContentType(contentType),
				validateHeaderValue(cacheControl, "cacheControl"));
	}

	@Override
	public void putBytes(byte[] data, String key, String contentType, String cacheControl) {
		assertRuntime();
		if (data == null || data.length == 0) {
			throw new IllegalArgumentException("data must not be empty");
		}
		writeBytes(
				key,
				data,
				validateContentType(contentType),
				validateHeaderValue(cacheControl, "cacheControl"));
	}

	@Override
	public InputStream getObjectStream(String key) {
		assertRuntime();
		lock.readLock().lock();
		try {
			Path object = resolveObject(key);
			assertRegularObject(object);
			readMetadata(object);
			return Files.newInputStream(object, StandardOpenOption.READ);
		} catch (IOException exception) {
			throw storageFailure("open", key, exception);
		} finally {
			lock.readLock().unlock();
		}
	}

	@Override
	public String publicUrl(String objectKey) {
		assertRuntime();
		validateLogicalKey(objectKey);
		if (StorageObjectKeys.isPrivateOrigin(objectKey)) {
			throw new IllegalArgumentException("Private-origin media objects do not have a public URL");
		}
		String capability = capabilityFor(objectKey);
		registerPublicCapability(capability, objectKey);
		return publicBaseUrl + PUBLIC_ROUTE + "/" + capability;
	}

	/**
	 * Resolves one unguessable public capability without ever accepting a caller
	 * controlled object key. The complete object is read under the storage lock
	 * and only after its recorded size, MIME and ETag have been revalidated.
	 */
	Optional<PublicObject> readPublicCapability(String capability) {
		assertRuntime();
		if (!isCapabilityShape(capability)) return Optional.empty();
		lock.readLock().lock();
		try {
			String logicalKey = publicCapabilities.get(capability);
			if (logicalKey == null) return Optional.empty();
			Path object = resolveObject(logicalKey);
			if (!Files.exists(object, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
			assertRegularObject(object);
			StoredMetadata metadata = readMetadata(object);
			if (metadata.sizeBytes() < 1 || metadata.sizeBytes() > maxResponseBytes) {
				return Optional.empty();
			}
			byte[] bytes = Files.readAllBytes(object);
			if (bytes.length != metadata.sizeBytes()) {
				throw new IllegalStateException("Simulation public object size changed while reading");
			}
			return Optional.of(new PublicObject(
					bytes,
					metadata.contentType(),
					metadata.cacheControl(),
					metadata.eTag()));
		} catch (IOException exception) {
			throw storageFailure("read public capability", "<opaque>", exception);
		} finally {
			lock.readLock().unlock();
		}
	}

	@Override
	public void deleteObject(String objectKey) {
		assertRuntime();
		lock.writeLock().lock();
		try {
			Path object = resolveObject(objectKey);
			Files.deleteIfExists(metadataPath(object));
			Files.deleteIfExists(object);
			if (!StorageObjectKeys.isPrivateOrigin(objectKey)) {
				publicCapabilities.remove(capabilityFor(objectKey), objectKey);
			}
			deleteEmptyParents(object.getParent(), namespaceRoot(objectKey));
		} catch (IOException exception) {
			throw storageFailure("delete", objectKey, exception);
		} finally {
			lock.writeLock().unlock();
		}
	}

	@Override
	public void downloadToFile(String key, Path target) {
		downloadToFile(key, target, Duration.ofSeconds(30));
	}

	@Override
	public void downloadToFile(String key, Path target, Duration timeout) {
		assertRuntime();
		validTtl(timeout, "timeout");
		Path destination = require(target, "target").toAbsolutePath().normalize();
		lock.readLock().lock();
		try {
			Path object = resolveObject(key);
			assertRegularObject(object);
			readMetadata(object);
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
					&& Files.isSymbolicLink(destination)) {
				throw new IllegalArgumentException("Download target must not be a symbolic link");
			}
			Path parent = destination.getParent();
			if (parent == null) throw new IllegalArgumentException("Download target has no parent");
			Files.createDirectories(parent);
			Files.copy(object, destination, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException exception) {
			throw storageFailure("download", key, exception);
		} finally {
			lock.readLock().unlock();
		}
	}

	@Override
	public void deleteFolder(String prefix) {
		deleteFolderInternal(prefix, null, false);
	}

	@Override
	public void deleteFolderExcept(String prefix, String retainedObjectKey) {
		deleteFolderInternal(prefix, retainedObjectKey, false);
	}

	@Override
	public void deleteFolderExceptPrefix(String prefix, String retainedPrefix) {
		deleteFolderInternal(prefix, retainedPrefix, true);
	}

	@Override
	public void invalidatePublicAsset(UUID assetId) {
		assertRuntime();
		if (assetId == null) throw new IllegalArgumentException("assetId is required");
		// Local files have no intermediary CDN cache to invalidate.
	}

	Path storageRootForTesting() {
		return storageRoot;
	}

	/**
	 * Deletes only the fixed simulation object namespaces for a destructive
	 * FRESH materialization. The persistent capability key and all run reports
	 * remain outside those namespaces.
	 */
	public void resetStorageForFresh() {
		assertRuntime();
		if (simulationMode != SimulationMode.FRESH) {
			throw new IllegalStateException("Simulation media storage reset requires FRESH mode");
		}
		Path expectedRoot = reportDirectory.resolve("media-storage-v1").normalize();
		if (!storageRoot.equals(expectedRoot)
				|| !privateRoot.equals(storageRoot.resolve(PRIVATE_NAMESPACE))
				|| !publicRoot.equals(storageRoot.resolve(PUBLIC_NAMESPACE))) {
			throw new IllegalStateException("Simulation media storage reset target is invalid");
		}

		lock.writeLock().lock();
		try {
			assertNoSymlinkComponents(reportDirectory);
			assertNoSymlinkComponents(storageRoot);
			assertCapabilityKeyUnchanged();
			List<Path> privatePaths = validatedResetPaths(privateRoot);
			List<Path> publicPaths = validatedResetPaths(publicRoot);
			runtimeGuard.assertRuntimeAllowed();
			deleteValidatedPaths(privatePaths, privateRoot);
			deleteValidatedPaths(publicPaths, publicRoot);
			uploadGrants.clear();
			publicCapabilities.clear();
		} catch (IOException failure) {
			throw new IllegalStateException("Unable to reset simulation media storage", failure);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void copyConditional(
			String sourceKey,
			String targetKey,
			String expectedETag,
			String replacementContentType,
			String replacementCacheControl
	) {
		lock.writeLock().lock();
		try {
			Path source = resolveObject(sourceKey);
			assertRegularObject(source);
			StoredMetadata sourceMetadata = readMetadata(source);
			if (!sourceMetadata.eTag().equals(expectedETag)) {
				throw new IllegalStateException("Conditional object copy rejected an ETag mismatch");
			}
			writeFileLocked(
					targetKey,
					source,
					replacementContentType == null
							? sourceMetadata.contentType() : replacementContentType,
					replacementCacheControl == null
							? sourceMetadata.cacheControl() : replacementCacheControl);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private List<Path> validatedResetPaths(Path namespace) throws IOException {
		if (!namespace.startsWith(storageRoot)
				|| namespace.getParent() == null
				|| !namespace.getParent().equals(storageRoot)) {
			throw new IllegalStateException("Simulation media reset namespace is invalid");
		}
		if (!Files.exists(namespace, LinkOption.NOFOLLOW_LINKS)) return List.of();
		if (!Files.isDirectory(namespace, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(namespace)) {
			throw new IllegalStateException("Simulation media reset namespace is unsafe");
		}
		List<Path> paths;
		try (var walk = Files.walk(namespace)) {
			paths = walk.limit(MAX_RESET_PATHS + 1L).toList();
		}
		if (paths.size() > MAX_RESET_PATHS) {
			throw new IllegalStateException("Simulation media reset exceeds its hard path bound");
		}
		for (Path path : paths) {
			if (!path.normalize().startsWith(namespace) || Files.isSymbolicLink(path)) {
				throw new IllegalStateException("Simulation media reset found an unsafe path");
			}
		}
		return paths;
	}

	private static void deleteValidatedPaths(List<Path> paths, Path namespace) throws IOException {
		for (Path path : paths.stream().sorted(Comparator.reverseOrder()).toList()) {
			if (!path.equals(namespace)) Files.deleteIfExists(path);
		}
	}

	private void writeBytes(String key, byte[] data, String contentType, String cacheControl) {
		lock.writeLock().lock();
		try {
			Path target = prepareTarget(key);
			Path temporary = Files.createTempFile(target.getParent(), ".simulation-object-", ".tmp");
			try {
				Files.write(
						temporary,
						data,
						StandardOpenOption.WRITE,
						StandardOpenOption.TRUNCATE_EXISTING);
				commitObject(temporary, target, contentType, cacheControl);
			} finally {
				Files.deleteIfExists(temporary);
			}
		} catch (IOException exception) {
			throw storageFailure("write", key, exception);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void writeFile(String key, Path source, String contentType, String cacheControl) {
		lock.writeLock().lock();
		try {
			writeFileLocked(key, source, contentType, cacheControl);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void writeFileLocked(String key, Path source, String contentType, String cacheControl) {
		Path temporary = null;
		try {
			Path target = prepareTarget(key);
			temporary = Files.createTempFile(target.getParent(), ".simulation-object-", ".tmp");
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			commitObject(temporary, target, contentType, cacheControl);
		} catch (IOException exception) {
			throw storageFailure("copy", key, exception);
		} finally {
			if (temporary != null) {
				try {
					Files.deleteIfExists(temporary);
				} catch (IOException ignored) {
					// A failed operation is already being surfaced; cleanup is best effort.
				}
			}
		}
	}

	private void commitObject(
			Path temporary,
			Path target,
			String contentType,
			String cacheControl
	) throws IOException {
		long size = Files.size(temporary);
		String eTag = quote(HexFormat.of().formatHex(digestFile(temporary)));
		moveReplacing(temporary, target);
		writeMetadataAtomic(target, new StoredMetadata(size, contentType, cacheControl, eTag));
	}

	private void writeMetadataAtomic(Path object, StoredMetadata metadata) throws IOException {
		Path target = metadataPath(object);
		String serialized = String.join("\n",
				METADATA_VERSION,
				Long.toString(metadata.sizeBytes()),
				metadata.eTag(),
				encode(metadata.contentType()),
				encode(metadata.cacheControl())) + "\n";
		Path temporary = Files.createTempFile(target.getParent(), ".simulation-meta-", ".tmp");
		try {
			Files.writeString(
					temporary,
					serialized,
					StandardCharsets.US_ASCII,
					StandardOpenOption.WRITE,
					StandardOpenOption.TRUNCATE_EXISTING);
			moveReplacing(temporary, target);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private StoredMetadata readMetadata(Path object) {
		Path metadataPath = metadataPath(object);
		try {
			if (!Files.isRegularFile(metadataPath, LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(metadataPath)) {
				throw new IllegalStateException("Simulation object metadata is missing or unsafe");
			}
			long metadataBytes = Files.size(metadataPath);
			if (metadataBytes < 1 || metadataBytes > MAX_METADATA_BYTES) {
				throw new IllegalStateException("Simulation object metadata exceeds its hard bound");
			}
			List<String> lines = Files.readAllLines(metadataPath, StandardCharsets.US_ASCII);
			if (lines.size() != 5 || !METADATA_VERSION.equals(lines.get(0))) {
				throw new IllegalStateException("Simulation object metadata has an invalid shape");
			}
			long recordedSize = Long.parseLong(lines.get(1));
			String recordedETag = lines.get(2);
			String contentType = validateContentType(decode(lines.get(3)));
			String cacheControl = validateHeaderValue(decode(lines.get(4)), "cacheControl");
			long actualSize = Files.size(object);
			String actualETag = quote(HexFormat.of().formatHex(digestFile(object)));
			if (recordedSize != actualSize || !recordedETag.equals(actualETag)) {
				throw new IllegalStateException("Simulation object failed metadata integrity verification");
			}
			return new StoredMetadata(actualSize, contentType, cacheControl, actualETag);
		} catch (IOException | IllegalArgumentException exception) {
			throw new IllegalStateException("Unable to read simulation object metadata", exception);
		}
	}

	private void deleteFolderInternal(String prefix, String retainedLogical, boolean retainSubtree) {
		assertRuntime();
		validateLogicalKey(prefix);
		lock.writeLock().lock();
		try {
			Path namespace = namespaceRoot(prefix);
			Path folder = resolveObject(prefix);
			if (!Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) return;
			assertSafePath(folder, namespace);
			Path retained = retainedLogical == null ? null : resolveObject(retainedLogical);
			if (retained != null && !retained.startsWith(folder)) {
				throw new IllegalArgumentException("Retained object must be inside deleted prefix");
			}
			List<Path> paths;
			try (var walk = Files.walk(folder)) {
				paths = walk.limit(MAX_RESET_PATHS + 1L).toList();
			}
			if (paths.size() > MAX_RESET_PATHS) {
				throw new IllegalStateException("Simulation prefix deletion exceeds its hard path bound");
			}
			for (Path path : paths.stream().sorted(Comparator.reverseOrder()).toList()) {
				if (Files.isSymbolicLink(path)) {
					throw new IllegalStateException("Simulation storage contains a symbolic link");
				}
				if (isRetained(path, retained, retainSubtree)) continue;
				Files.deleteIfExists(path);
			}
			prunePublicCapabilities(prefix, retainedLogical, retainSubtree);
			deleteEmptyParents(folder.getParent(), namespace);
		} catch (IOException exception) {
			throw storageFailure("delete folder", prefix, exception);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private boolean isRetained(Path path, Path retained, boolean retainSubtree) {
		if (retained == null) return false;
		if (retained.startsWith(path)) return true;
		if (retainSubtree && path.startsWith(retained)) return true;
		return path.equals(retained) || path.equals(metadataPath(retained));
	}

	private Path prepareTarget(String logicalKey) throws IOException {
		Path target = resolveObject(logicalKey);
		Path namespace = namespaceRoot(logicalKey);
		ensureSafeDirectories(target.getParent(), namespace);
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
			throw new IllegalStateException("Simulation object target is a symbolic link");
		}
		Path metadata = metadataPath(target);
		if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(metadata)) {
			throw new IllegalStateException("Simulation object metadata target is a symbolic link");
		}
		return target;
	}

	private static Path metadataPath(Path object) {
		Path fileName = object.getFileName();
		if (fileName == null) {
			throw new IllegalArgumentException("Simulation object path has no filename");
		}
		return object.resolveSibling(fileName + METADATA_SUFFIX);
	}

	private Path resolveObject(String logicalKey) {
		validateLogicalKey(logicalKey);
		Path namespace = namespaceRoot(logicalKey);
		String physicalKey = StorageObjectKeys.physicalKey(logicalKey);
		Path resolved = namespace.resolve(physicalKey.replace('/', java.io.File.separatorChar))
				.normalize();
		if (!resolved.startsWith(namespace)) {
			throw new IllegalArgumentException("Simulation object key escapes its namespace");
		}
		assertSafePath(resolved, namespace);
		return resolved;
	}

	private Path namespaceRoot(String logicalKey) {
		return StorageObjectKeys.isPrivateOrigin(logicalKey) ? privateRoot : publicRoot;
	}

	private void initializeRoots(Path reports) {
		try {
			assertNoSymlinkComponents(reports);
			Files.createDirectories(reports);
			if (Files.isSymbolicLink(reports)) {
				throw new IllegalStateException("Simulation report-directory must not be a symbolic link");
			}
			ensureSafeDirectories(privateRoot, storageRoot);
			ensureSafeDirectories(publicRoot, storageRoot);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to initialize simulation storage", exception);
		}
	}

	private byte[] loadOrCreateCapabilityKey() {
		lock.writeLock().lock();
		Path keyPath = storageRoot.resolve(CAPABILITY_KEY_FILE).normalize();
		try {
			assertSafePath(keyPath, storageRoot);
			if (Files.exists(keyPath, LinkOption.NOFOLLOW_LINKS)) {
				return readCapabilityKey(keyPath);
			}
			assertUnclaimedStorageIsEmpty();
			byte[] generated = new byte[TOKEN_BYTES];
			secureRandom.nextBytes(generated);
			try {
				Files.write(
						keyPath,
						generated,
						StandardOpenOption.CREATE_NEW,
						StandardOpenOption.WRITE);
				return generated;
			} catch (FileAlreadyExistsException concurrentCreator) {
				return readCapabilityKey(keyPath);
			}
		} catch (IOException exception) {
			throw new IllegalStateException(
					"Unable to initialize simulation media capability key", exception);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void assertUnclaimedStorageIsEmpty() throws IOException {
		try (var walk = Files.walk(storageRoot)) {
			int visited = 0;
			for (Path path : (Iterable<Path>) walk::iterator) {
				if (++visited > MAX_RESET_PATHS) {
					throw new IllegalStateException(
							"Unclaimed simulation storage exceeds its hard path bound");
				}
				if (path.equals(storageRoot)
						|| path.equals(privateRoot)
						|| path.equals(publicRoot)
						|| path.equals(storageRoot.resolve(CAPABILITY_KEY_FILE))) {
					continue;
				}
				throw new IllegalStateException(
						"Refusing to claim a non-empty simulation media storage directory");
			}
		}
	}

	private byte[] readCapabilityKey(Path keyPath) throws IOException {
		if (!Files.isRegularFile(keyPath, LinkOption.NOFOLLOW_LINKS)
				|| Files.isSymbolicLink(keyPath)) {
			throw new IllegalStateException("Simulation media capability key is unsafe");
		}
		byte[] key = Files.readAllBytes(keyPath);
		if (key.length != TOKEN_BYTES) {
			throw new IllegalStateException("Simulation media capability key has an invalid size");
		}
		return key;
	}

	private void assertCapabilityKeyUnchanged() throws IOException {
		byte[] persisted = readCapabilityKey(
				storageRoot.resolve(CAPABILITY_KEY_FILE).normalize());
		if (!MessageDigest.isEqual(publicCapabilityKey, persisted)) {
			throw new IllegalStateException(
					"Simulation media capability key changed after initialization");
		}
	}

	private void indexExistingPublicObjects() {
		lock.writeLock().lock();
		try (var walk = Files.walk(publicRoot)) {
			int indexed = 0;
			int visited = 0;
			for (Path path : (Iterable<Path>) walk::iterator) {
				if (++visited > MAX_RESET_PATHS) {
					throw new IllegalStateException(
							"Simulation public storage index exceeds its hard path bound");
				}
				if (Files.isSymbolicLink(path)) {
					throw new IllegalStateException(
							"Simulation public storage contains a symbolic link");
				}
				if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
						|| path.getFileName().toString().endsWith(METADATA_SUFFIX)) {
					continue;
				}
				if (++indexed > maxPublicCapabilities) {
					throw new IllegalStateException(
							"Simulation public media capability index exceeds its hard bound");
				}
				assertRegularObject(path);
				readMetadata(path);
				String logicalKey = StreamSupport.stream(
						publicRoot.relativize(path).spliterator(), false)
						.map(Path::toString)
						.collect(Collectors.joining("/"));
				validateLogicalKey(logicalKey);
				registerPublicCapability(capabilityFor(logicalKey), logicalKey);
			}
		} catch (IOException exception) {
			throw new IllegalStateException(
					"Unable to rebuild simulation public media capabilities", exception);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void registerPublicCapability(String capability, String logicalKey) {
		lock.writeLock().lock();
		try {
			String existing = publicCapabilities.get(capability);
			if (existing != null) {
				if (!existing.equals(logicalKey)) {
					throw new IllegalStateException("Simulation media capability collision");
				}
				return;
			}
			if (publicCapabilities.size() >= maxPublicCapabilities) {
				throw new IllegalStateException(
						"Simulation public media capability capacity is exhausted");
			}
			publicCapabilities.put(capability, logicalKey);
		} finally {
			lock.writeLock().unlock();
		}
	}

	private void prunePublicCapabilities(
			String prefix,
			String retainedLogical,
			boolean retainSubtree
	) {
		if (StorageObjectKeys.isPrivateOrigin(prefix)) return;
		publicCapabilities.entrySet().removeIf(entry -> {
			String key = entry.getValue();
			if (!isSameOrDescendant(key, prefix)) return false;
			if (retainedLogical == null) return true;
			if (retainSubtree) return !isSameOrDescendant(key, retainedLogical);
			return !key.equals(retainedLogical);
		});
	}

	private static boolean isSameOrDescendant(String value, String prefix) {
		return value.equals(prefix) || value.startsWith(prefix + "/");
	}

	private void ensureSafeDirectories(Path directory, Path floor) throws IOException {
		if (directory == null || !directory.normalize().startsWith(floor.normalize())) {
			throw new IllegalArgumentException("Simulation storage directory escapes its root");
		}
		Files.createDirectories(floor);
		if (Files.isSymbolicLink(floor)) {
			throw new IllegalStateException("Simulation storage root must not be a symbolic link");
		}
		Path relative = floor.relativize(directory);
		Path current = floor;
		for (Path segment : relative) {
			current = current.resolve(segment);
			if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
				if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
					throw new IllegalStateException("Simulation storage path contains an unsafe segment");
				}
			} else {
				Files.createDirectory(current);
			}
		}
	}

	private void assertSafePath(Path path, Path floor) {
		if (!path.normalize().startsWith(floor.normalize())) {
			throw new IllegalArgumentException("Simulation storage path escapes its namespace");
		}
		assertNoSymlinkComponents(storageRoot);
		Path current = floor;
		if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
			throw new IllegalStateException("Simulation storage namespace is a symbolic link");
		}
		for (Path segment : floor.relativize(path)) {
			current = current.resolve(segment);
			if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
				throw new IllegalStateException("Simulation storage path contains a symbolic link");
			}
		}
	}

	private static void assertNoSymlinkComponents(Path path) {
		Path absolute = path.toAbsolutePath().normalize();
		Path current = absolute.getRoot();
		if (current == null) {
			throw new IllegalArgumentException("Simulation storage path must be absolute");
		}
		for (Path segment : absolute) {
			current = current.resolve(segment);
			if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
				throw new IllegalStateException("Simulation storage path contains a symbolic link");
			}
		}
	}

	private void assertRegularObject(Path object) {
		Path namespace = object.startsWith(privateRoot) ? privateRoot : publicRoot;
		assertSafePath(object, namespace);
		if (!Files.isRegularFile(object, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(object)) {
			throw new IllegalStateException("Simulation object does not exist or is unsafe");
		}
	}

	private void deleteEmptyParents(Path directory, Path floor) throws IOException {
		Path current = directory;
		while (current != null && !current.equals(floor) && current.startsWith(floor)) {
			try (var children = Files.list(current)) {
				if (children.findAny().isPresent()) return;
			}
			Files.deleteIfExists(current);
			current = current.getParent();
		}
	}

	private void purgeExpiredGrants() {
		Instant now = clock.instant();
		uploadGrants.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
	}

	private String parseUploadToken(String uploadUrl) {
		try {
			URI uri = URI.create(uploadUrl);
			if (!"simulation-upload".equals(uri.getScheme())
					|| !"local".equals(uri.getHost())
					|| uri.getQuery() != null
					|| uri.getFragment() != null
					|| uri.getPath() == null
					|| uri.getPath().length() < 2
					|| uri.getPath().indexOf('/', 1) >= 0) {
				throw new IllegalArgumentException("Invalid simulation upload capability URL");
			}
			String token = uri.getPath().substring(1);
			byte[] decoded = URL_DECODER.decode(token);
			if (decoded.length != TOKEN_BYTES) {
				throw new IllegalArgumentException("Invalid simulation upload capability token");
			}
			return token;
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Invalid simulation upload capability URL", exception);
		}
	}

	private String newToken() {
		byte[] bytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(bytes);
		return URL_ENCODER.encodeToString(bytes);
	}

	private String capabilityFor(String logicalKey) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(publicCapabilityKey, "HmacSHA256"));
			mac.update(CAPABILITY_DOMAIN.getBytes(StandardCharsets.UTF_8));
			return URL_ENCODER.encodeToString(
					mac.doFinal(logicalKey.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException impossible) {
			throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
		}
	}

	private static boolean isCapabilityShape(String capability) {
		if (capability == null || capability.length() != 43
				|| capability.chars().anyMatch(character ->
						!(character >= 'A' && character <= 'Z')
								&& !(character >= 'a' && character <= 'z')
								&& !(character >= '0' && character <= '9')
								&& character != '-'
								&& character != '_')) {
			return false;
		}
		try {
			return URL_DECODER.decode(capability).length == TOKEN_BYTES;
		} catch (IllegalArgumentException invalid) {
			return false;
		}
	}

	static String validatePublicBaseUrl(String value) {
		if (isBlank(value) || value.length() > 512 || !value.equals(value.trim())) {
			throw new IllegalArgumentException("publicBaseUrl is invalid");
		}
		try {
			URI uri = URI.create(value);
			String scheme = uri.getScheme();
			String path = uri.getRawPath();
			if ((!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)))
					|| isBlank(uri.getHost())
					|| uri.getRawUserInfo() != null
					|| uri.getRawQuery() != null
					|| uri.getRawFragment() != null
					|| (path != null && !path.isEmpty() && !"/".equals(path))
					|| uri.getPort() == 0
					|| uri.getPort() > 65_535) {
				throw new IllegalArgumentException("publicBaseUrl must be an HTTP(S) origin");
			}
			return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
		} catch (IllegalArgumentException invalid) {
			throw new IllegalArgumentException("publicBaseUrl must be an HTTP(S) origin", invalid);
		}
	}

	private static void validateLogicalKey(String logicalKey) {
		if (isBlank(logicalKey)
				|| !logicalKey.equals(logicalKey.trim())
				|| logicalKey.startsWith("/")
				|| logicalKey.contains("\\")
				|| logicalKey.endsWith(METADATA_SUFFIX)
				|| logicalKey.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Simulation object key is unsafe");
		}
		for (String segment : logicalKey.split("/", -1)) {
			if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
				throw new IllegalArgumentException("Simulation object key is unsafe");
			}
		}
	}

	private static String validateContentType(String value) {
		String normalized = validateHeaderValue(value, "contentType").toLowerCase(java.util.Locale.ROOT);
		if (!normalized.matches("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")) {
			throw new IllegalArgumentException("contentType is invalid");
		}
		return normalized;
	}

	private static String validateHeaderValue(String value, String field) {
		if (isBlank(value) || !value.equals(value.trim())
				|| value.length() > 512
				|| value.chars().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(field + " is invalid");
		}
		return value;
	}

	private void assertRuntime() {
		runtimeGuard.assertRuntimeAllowed();
	}

	private static Duration validTtl(Duration value, String field) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(field + " must be positive");
		}
		return value;
	}

	private static <T> T require(T value, String field) {
		if (value == null) throw new IllegalArgumentException(field + " is required");
		return value;
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private static String encode(String value) {
		return URL_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String decode(String value) {
		return new String(URL_DECODER.decode(value), StandardCharsets.UTF_8);
	}

	private static byte[] digestFile(Path path) throws IOException {
		MessageDigest digest = sha256Digest();
		try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ)) {
			byte[] buffer = new byte[8_192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read > 0) digest.update(buffer, 0, read);
			}
		}
		return digest.digest();
	}

	private static byte[] sha256(byte[] bytes) {
		return sha256Digest().digest(bytes);
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static String quote(String value) {
		return '"' + value + '"';
	}

	private static void moveReplacing(Path source, Path target) throws IOException {
		try {
			Files.move(source, target,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException unsupported) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static IllegalStateException storageFailure(
			String operation,
			String key,
			Exception exception
	) {
		return new IllegalStateException(
				"Simulation storage could not " + operation + " object " + safeKey(key), exception);
	}

	private static String safeKey(String key) {
		if (key == null) return "<null>";
		return key.length() <= 160 ? key : key.substring(0, 160) + "...";
	}

	private record UploadGrant(
			String objectKey,
			String contentType,
			long sizeBytes,
			Instant expiresAt
	) {
	}

	private record StoredMetadata(
			long sizeBytes,
			String contentType,
			String cacheControl,
			String eTag
	) {
	}

	record PublicObject(
			byte[] bytes,
			String contentType,
			String cacheControl,
			String eTag
	) {
		PublicObject {
			bytes = bytes.clone();
		}

		@Override
		public byte[] bytes() {
			return bytes.clone();
		}
	}
}
