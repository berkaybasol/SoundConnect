package com.berkayb.soundconnect.modules.media.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface StorageClient {
	
	// client buraya yukler
	String createPresignedPutUrl(String objectKey, String mimeType, long sizeBytes);

	/**
	 * Creates a short-lived origin URL. Callers must authorize the requesting
	 * principal before invoking this method and must not persist the result.
	 */
	StorageAccessUrl createPresignedGetUrl(String objectKey);

	/**
	 * Reads authoritative object metadata. An empty result means the key does not exist.
	 */
	Optional<StorageObjectMetadata> getObjectMetadata(String objectKey);

	/**
	 * Atomically snapshots a presigned-upload object into a server-only key. The
	 * copy must be conditional on the authoritative source ETag so the validated
	 * bytes cannot change between HEAD and COPY.
	 */
	void copyUploadToImmutable(String uploadObjectKey, String immutableObjectKey, String expectedETag);

	/**
	 * Copies a signature-validated immutable public-intent source from the
	 * private origin to its deterministic public-bucket key.
	 */
	void promoteVerifiedObject(
			String verifiedObjectKey,
			String publicObjectKey,
			String contentType,
			String cacheControl,
			String expectedVerifiedETag
	);
	
	/**
	 *
	 * @param local Yuklenecek yerel dosya (orn:.../master/m3u8
	 * @param key  Object key (Orn: media/{assetId}/hls/master.m3u8)
	 * @param contentType HTTP Content - Type (orn: application/vnd.apple.mpegurl)
	 * @param cacheControl Cache-Control basligi (orn: public, max-age=30)
	 */
	void putFile(Path local, String key, String contentType, String cacheControl);
	
	// bellekteki veriyi (kucuk dosyalar/manifest) S3'e yukler.
	void putBytes(byte[] data, String key, String contentType, String cacheControl);
	
	// object'i stream olarak ac (buyuk dosyalarda stream ederek yazmak icin)
	// kullanan taraf akisi kapatmakla yukumludur
	InputStream getObjectStream(String key);
	
	// public erisim URL'si (CDN ustunden)
	String publicUrl(String objectKey);
	
	// dosya sil
	void deleteObject(String objectKey);
	
	// Objetc'i dogrudan hedef dosyaya indir
	void downloadToFile(String key, Path target);

	/**
	 * Downloads with an operation-specific hard deadline. Implementations that
	 * cannot override a request deadline retain their normal bounded behavior.
	 */
	default void downloadToFile(String key, Path target, Duration timeout) {
		downloadToFile(key, target);
	}
	
	// prefix altindaki dosyalari sil(orn. HLS klasoru)
	void deleteFolder(String prefix);

	/** Deletes an attempt prefix while preserving one exact committed object. */
	void deleteFolderExcept(String prefix, String retainedObjectKey);

	/** Deletes an attempt prefix while preserving one complete winner subtree. */
	void deleteFolderExceptPrefix(String prefix, String retainedPrefix);

	/**
	 * Purges every public CDN representation for exactly one media asset. The
	 * implementation must scope the request to the configured media root and
	 * asset UUID; callers cannot supply arbitrary invalidation paths. A deployment
	 * without a CDN distribution id treats this as a no-op and relies on the
	 * bounded public cache policy instead.
	 */
	void invalidatePublicAsset(UUID assetId);
}
