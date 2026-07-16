package com.berkayb.soundconnect.modules.media.storage;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateInvalidationRequest;
import software.amazon.awssdk.services.cloudfront.model.InvalidationBatch;
import software.amazon.awssdk.services.cloudfront.model.Paths;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * StorageClient'in S3 uyarlamasi.
 * Presigned PUT ile istemci dosyayi dogrudan S3'e yukler
 * Public URL uretimi CloudFront (cdnBaseUrl) uzerinden calisir.(OAC var)
 * Silme islemleri tek obje veya prefix(HLS klasoru) bazinda yapilir
 */
@Component
@Slf4j
public class S3StorageClient implements StorageClient{

	private static final Pattern CLOUDFRONT_DISTRIBUTION_ID = Pattern.compile("E[A-Z0-9]{5,31}");
	private static final Pattern SAFE_MEDIA_ROOT = Pattern.compile(
			"[A-Za-z0-9][A-Za-z0-9_-]*(/[A-Za-z0-9][A-Za-z0-9_-]*)*");
	
	// s3 bucket name'i yml'den cekiyoruz
	@Value("${cloud.storage.bucket}")
	private String bucket;

	/**
	 * Protected media is stored in an origin bucket that is not attached to the
	 * public CDN distribution. Production configuration must keep it distinct
	 * from {@link #bucket}.
	 */
	@Value("${cloud.storage.privateBucket}")
	private String privateBucket;
	
	// bucket'a bagli region yml'den cekiyoruz
	@Value("${cloud.storage.region}")
	private String region;
	
	// s3 acces key yml'den cekiyoruz
	@Value("${cloud.storage.accessKey:}")
	private String accessKey;
	
	@Value("${cloud.storage.secretKey:}")
	private String secretKey;
	
	@Value("${cloud.storage.endpoint:}") // AWS S3 icin bos birakiyoruz R2'ye gecersek kullanilacak.
	private String endpoint;
	
	@Value("${cloud.storage.cdnBaseUrl:}") // CloudFront base URL
	private String cdnBaseUrl;
	
	@Value("${cloud.storage.pathStyleAccess:false}") // AWS false R2 true olcak
	private boolean pathStyleAccess;
	
	@Value("${cloud.storage.presign.uploadExpirySeconds:${cloud.storage.presign.expirySeconds:900}}")
	private int uploadPresignExpirySeconds;

	@Value("${cloud.storage.presign.downloadExpirySeconds:300}")
	private int downloadPresignExpirySeconds;
	
	@Value("${cloud.storage.aclPublicReadOnPut:false}") // OAC var o yuzden false
	private boolean aclPublicReadOnPut;

	/** Presence enables asset-scoped invalidation; empty remains valid for local/S3-compatible use. */
	@Value("${cloud.storage.cloudfront.distributionId:}")
	private String cloudFrontDistributionId = "";

	@Value("${media.paths.root:media}")
	private String mediaRoot = "media";

	@Value("${cloud.storage.cache.publicMaxAgeSeconds:300}")
	private int publicCacheMaxAgeSeconds = 300;

	@Value("${cloud.storage.cache.sharedMaxAgeSeconds:3600}")
	private int publicSharedCacheMaxAgeSeconds = 3600;

	@Value("${media.upload-verification.storage-call-timeout:PT30S}")
	private Duration storageCallTimeout = Duration.ofSeconds(30);

	// s3'e gercek cagrilari yapan client
	private S3Client s3;
	// presigned url ureten client
	private S3Presigner presigner;
	private CloudFrontClient cloudFront;
	
	
	// yapilandirmaya gore client'lari kur
	@PostConstruct
	void  init() {
		if (!StringUtils.hasText(privateBucket) || privateBucket.equals(bucket)) {
			throw new IllegalStateException(
					"cloud.storage.privateBucket must be configured and distinct from cloud.storage.bucket"
			);
		}
		if (!StringUtils.hasText(cdnBaseUrl) && !aclPublicReadOnPut) {
			throw new IllegalStateException(
					"cloud.storage.cdnBaseUrl is required when uploaded objects are not public-read"
			);
		}
		validatePresignExpiry("uploadExpirySeconds", uploadPresignExpirySeconds);
		validatePresignExpiry("downloadExpirySeconds", downloadPresignExpirySeconds);
		validateCacheBounds();
		validateMediaRoot();
		if (storageCallTimeout == null
				|| storageCallTimeout.isZero()
				|| storageCallTimeout.isNegative()) {
			throw new IllegalStateException(
					"media.upload-verification.storage-call-timeout must be positive");
		}
		if (StringUtils.hasText(cloudFrontDistributionId)
				&& !CLOUDFRONT_DISTRIBUTION_ID.matcher(cloudFrontDistributionId).matches()) {
			throw new IllegalStateException("cloud.storage.cloudfront.distributionId has an invalid format");
		}
		// kimlik bilgileri (env'de tanimladik)
		AwsCredentialsProvider credentials = credentialsProvider();
		
		// S3 Client config (timeout + path-style)
		var s3Cfg = S3Configuration.builder()
				.pathStyleAccessEnabled(pathStyleAccess) // R2' de true gerekebilir.
				.build();
		
		// timeout gibi client genel ayarlari
		var override = ClientOverrideConfiguration.builder()
		        .apiCallTimeout(storageCallTimeout) // tum cagri icin ust sinir
		        .apiCallAttemptTimeout(storageCallTimeout) // tek attempt ust siniri
		        .build();
		
		// S3Client builder'i HTTP client + cred + overrides + s3 config
		var s3Builder = S3Client.builder()
				.httpClient(UrlConnectionHttpClient.create())
				.credentialsProvider(credentials)
				.overrideConfiguration(override)
				.serviceConfiguration(s3Cfg);
		
		// presigner builder'i imzali url uretimi icin
		var presignerBuilder = S3Presigner.builder()
				.credentialsProvider(credentials)
				.serviceConfiguration(s3Cfg);
		
		// Endpoints vs Region:
		// AWS S3: region kullan (endpoint bos)
		// R2/MinIO: endpointOverride ver: yine de bir region belirtiyoruz imza icin. yoksa us-east-1 guvvenli secim
		if (StringUtils.hasText(endpoint)) {
			var effRegion = StringUtils.hasText(region) ? region : "us-east-1";
			s3Builder.endpointOverride(URI.create(endpoint)).region(Region.of(effRegion));
			presignerBuilder.endpointOverride(URI.create(endpoint)).region(Region.of(effRegion));
		} else {
			s3Builder.region(Region.of(region));
			presignerBuilder.region(Region.of(region));
		}
		
		// clientlari olustur
		this.s3 = s3Builder.build();
		this.presigner = presignerBuilder.build();
		if (StringUtils.hasText(cloudFrontDistributionId)) {
			this.cloudFront = CloudFrontClient.builder()
					.httpClient(UrlConnectionHttpClient.create())
					.credentialsProvider(credentials)
					.overrideConfiguration(override)
					.region(Region.AWS_GLOBAL)
					.build();
		}
		
		log.info("[storage] S3StorageClient initialized publicBucket={} privateBucket={} region={} endpoint={} pathStyle={} cdnBaseUrl={} aclPublicReadOnPut={} cloudFrontInvalidation={}",
		         bucket, privateBucket, region, endpoint, pathStyleAccess, cdnBaseUrl,
				aclPublicReadOnPut, cloudFront != null);
	}

	@PreDestroy
	void close() {
		if (presigner != null) presigner.close();
		if (s3 != null) s3.close();
		if (cloudFront != null) cloudFront.close();
	}

	private void validatePresignExpiry(String property, int seconds) {
		if (seconds < 60 || seconds > 3600) {
			throw new IllegalStateException(
					"cloud.storage.presign." + property + " must be between 60 and 3600"
			);
		}
	}

	private void validateCacheBounds() {
		if (publicCacheMaxAgeSeconds < 0 || publicCacheMaxAgeSeconds > 3600) {
			throw new IllegalStateException("cloud.storage.cache.publicMaxAgeSeconds must be between 0 and 3600");
		}
		if (publicSharedCacheMaxAgeSeconds < 0 || publicSharedCacheMaxAgeSeconds > 86400) {
			throw new IllegalStateException("cloud.storage.cache.sharedMaxAgeSeconds must be between 0 and 86400");
		}
	}

	private void validateMediaRoot() {
		if (!StringUtils.hasText(mediaRoot)
				|| !mediaRoot.equals(mediaRoot.trim())
				|| !SAFE_MEDIA_ROOT.matcher(mediaRoot).matches()) {
			throw new IllegalStateException("media.paths.root is unsafe for CDN invalidation");
		}
	}

	private AwsCredentialsProvider credentialsProvider() {
		boolean hasAccessKey = StringUtils.hasText(accessKey);
		boolean hasSecretKey = StringUtils.hasText(secretKey);
		if (hasAccessKey != hasSecretKey) {
			throw new IllegalStateException("Both cloud.storage.accessKey and cloud.storage.secretKey must be configured together");
		}
		if (hasAccessKey) {
			return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey));
		}
		log.info("[storage] explicit S3 credentials not configured; using the AWS default credentials provider chain");
		return DefaultCredentialsProvider.create();
	}
	
	// PRESIGNED PUT: istemcinin dogrudan S3'e yuklemesi icin imzali URL
	@Override
	public String createPresignedPutUrl(String objectKey, String mimeType, long sizeBytes) {
		if (sizeBytes <= 0) {
			throw new IllegalArgumentException("sizeBytes must be greater than zero");
		}
		if (StorageObjectKeys.isVerified(objectKey)) {
			throw new IllegalArgumentException("Server-verified media keys can never receive client write authority");
		}
		StorageLocation location = locate(objectKey);
		// put sirasinda objeye eklencek metadata/basliklar
		var putReqBuilder = PutObjectRequest.builder()
				.bucket(location.bucket())
				.key(location.key())
				.contentType(mimeType)
				.contentLength(sizeBytes);  // SigV4 beyan edilen boyutu da sabitler.
		
		// OAC (Origin Access Control) kullaniyoruz o yuzden public acl vermiyoruz false birakiyoruz
		if (aclPublicReadOnPut && !location.privateOrigin()) {
			putReqBuilder.acl(ObjectCannedACL.PUBLIC_READ);
		}
		
		var putReq = putReqBuilder.build();
		
		// presigned url suresi ( guvenlik icin kisa tutcaz)
		var presignReq = PutObjectPresignRequest.builder()
				.putObjectRequest(putReq)
				.signatureDuration(Duration.ofSeconds(uploadPresignExpirySeconds))
				.build();
		
		// url uret ve dondur
		URL url = presigner.presignPutObject(presignReq).url();
		return url.toString();
	}

	@Override
	public StorageAccessUrl createPresignedGetUrl(String objectKey) {
		StorageLocation location = locate(objectKey);
		if (!location.protectedObject()) {
			throw new IllegalArgumentException("Presigned GET is reserved for protected media objects");
		}
		GetObjectRequest getObjectRequest = GetObjectRequest.builder()
				.bucket(location.bucket())
				.key(location.key())
				.responseCacheControl("private, no-store, max-age=0")
				.build();
		GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
				.getObjectRequest(getObjectRequest)
				.signatureDuration(Duration.ofSeconds(downloadPresignExpirySeconds))
				.build();
		Instant expiresAt = Instant.now().plusSeconds(downloadPresignExpirySeconds);
		return new StorageAccessUrl(presigner.presignGetObject(presignRequest).url().toString(), expiresAt);
	}

	@Override
	public Optional<StorageObjectMetadata> getObjectMetadata(String objectKey) {
		StorageLocation location = locate(objectKey);
		try {
			HeadObjectResponse response = s3.headObject(HeadObjectRequest.builder()
					.bucket(location.bucket())
					.key(location.key())
					.build());
			return Optional.of(new StorageObjectMetadata(
					response.contentLength(), response.contentType(), response.eTag()));
		} catch (S3Exception exception) {
			if (exception.statusCode() == 404) {
				return Optional.empty();
			}
			throw new RuntimeException("S3 headObject failed for key=" + objectKey, exception);
		}
	}

	@Override
	public void copyUploadToImmutable(
			String uploadObjectKey,
			String immutableObjectKey,
			String expectedETag
	) {
		if (!StorageObjectKeys.isImmutableSnapshotForUpload(uploadObjectKey, immutableObjectKey)
				|| !StringUtils.hasText(expectedETag)) {
			throw new IllegalArgumentException("Invalid immutable upload snapshot request");
		}

		StorageLocation source = locate(uploadObjectKey);
		StorageLocation target = locate(immutableObjectKey);
		if (!source.privateOrigin()
				|| source.verifiedObject()
				|| (!source.quarantinedObject() && !source.protectedObject())
				|| !target.privateOrigin()
				|| !target.verifiedObject()) {
			throw new IllegalArgumentException("Immutable snapshots must remain inside the private origin");
		}

		CopyObjectRequest copyRequest = baseCopyRequest(source, target)
				.copySourceIfMatch(expectedETag)
				.build();
		s3.copyObject(copyRequest);
		log.info("[storage] snapshotted upload into immutable private object sourceKey={} targetKey={}",
				uploadObjectKey, immutableObjectKey);
	}

	@Override
	public void promoteVerifiedObject(
			String verifiedObjectKey,
			String publicObjectKey,
			String contentType,
			String cacheControl,
			String expectedVerifiedETag
	) {
		String expectedPublicKey = StorageObjectKeys.publicKeyForVerified(verifiedObjectKey);
		if (!expectedPublicKey.equals(publicObjectKey)
				|| StorageObjectKeys.isPrivateOrigin(publicObjectKey)
				|| !StringUtils.hasText(contentType)
				|| !StringUtils.hasText(cacheControl)
				|| !StringUtils.hasText(expectedVerifiedETag)) {
			throw new IllegalArgumentException("Invalid verified promotion target");
		}

		StorageLocation source = locate(verifiedObjectKey);
		StorageLocation target = locate(publicObjectKey);
		if (!source.verifiedObject() || !source.privateOrigin() || target.privateOrigin()) {
			throw new IllegalArgumentException("Verified promotion must copy from private origin to public origin");
		}

		// Public keys are content-addressed by the immutable asset id and are never
		// overwritten by another upload. Replace client-controlled upload metadata
		// with authoritative delivery headers. The central cache-policy ceiling
		// preserves the deletion SLA even when CDN invalidation is not configured.
		CopyObjectRequest.Builder copyRequest = baseCopyRequest(source, target)
				.copySourceIfMatch(expectedVerifiedETag)
				.metadataDirective(MetadataDirective.REPLACE)
				.contentType(contentType)
				.cacheControl(boundedPublicCacheControl(cacheControl));
		if (aclPublicReadOnPut) {
			copyRequest.acl(ObjectCannedACL.PUBLIC_READ);
		}
		s3.copyObject(copyRequest.build());
		log.info("[storage] promoted verified object sourceKey={} targetKey={}",
				verifiedObjectKey, publicObjectKey);
	}

	private CopyObjectRequest.Builder baseCopyRequest(StorageLocation source, StorageLocation target) {
		String encodedSourceKey = URLEncoder.encode(source.key(), StandardCharsets.UTF_8)
				.replace("+", "%20");
		return CopyObjectRequest.builder()
				.copySource(source.bucket() + "/" + encodedSourceKey)
				.destinationBucket(target.bucket())
				.destinationKey(target.key())
				.metadataDirective(MetadataDirective.COPY);
	}
	
	
	// PUBLIC URL: Playback/thumnail gosterimi icin erisim adresi
	@Override
	public String publicUrl(String objectKey) {
		StorageLocation location = locate(objectKey);
		if (location.privateOrigin()) {
			throw new IllegalArgumentException("Private-origin media objects do not have a public URL");
		}
		// cloudfront tanimliysa her zaman cdn'i kullan (oac senaryosunda onerilen yol)
		if (StringUtils.hasText(cdnBaseUrl)) {
			return stripTrailingSlash(cdnBaseUrl) + "/" + location.key();
		}
		// CDN yoksa ve custom endpoint (R2/MinIO) kullanirsak path-style ile olustturuoruz
		if (StringUtils.hasText(endpoint)) {
			// pathStyleAccess = false olsa cogu S3-compatible'da guvenli yol
			return stripTrailingSlash(endpoint) + "/" + bucket + "/" + location.key();
		}
		// AWS S3 default virtual-hosted style
		return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + location.key();
	}
	
	// tek obje silme
	@Override
	public void deleteObject(String objectKey) {
		StorageLocation location = locate(objectKey);
		s3.deleteObject(DeleteObjectRequest.builder()
				                .bucket(location.bucket())
				                .key(location.key())
				                .build());
		log.info("[storage] deleted object key={}", objectKey);
	}
	
	// prefix altindaki tum objeleri silme (orn HLS klasoru)
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

	private void deleteFolderInternal(
			String prefix,
			String retainedLogicalKey,
			boolean retainSubtree
	) {
		// S3 has no folders. Delete each listed page in one batch (the API limit is
		// 1000 objects) so a large HLS tree cannot turn into thousands of sequential
		// network round trips. Durable callers retry any partial/provider failure.
		StorageLocation location = locate(prefix);
		String retainedPhysicalKey = null;
		if (StringUtils.hasText(retainedLogicalKey)) {
			StorageLocation retained = locate(retainedLogicalKey);
			if (!retained.bucket().equals(location.bucket())
					|| !retained.key().startsWith(ensureTrailingSlash(location.key()))) {
				throw new IllegalArgumentException("Retained key must be inside the deleted prefix");
			}
			retainedPhysicalKey = retainSubtree
					? ensureTrailingSlash(retained.key())
					: retained.key();
		}
		String continuation = null;
		var normalized = ensureTrailingSlash(location.key());
		do {
			var listReq = ListObjectsV2Request.builder()
					.bucket(location.bucket())
					.prefix(normalized)
					.continuationToken(continuation)
					.build();
			
			var listRes = s3.listObjectsV2(listReq);
			
			if (listRes.hasContents()) {
				String retainedKey = retainedPhysicalKey;
				List<ObjectIdentifier> objects = listRes.contents().stream()
						.filter(object -> retainedKey == null
								|| (retainSubtree
								? !object.key().startsWith(retainedKey)
								: !object.key().equals(retainedKey)))
						.map(object -> ObjectIdentifier.builder().key(object.key()).build())
						.toList();
				if (!objects.isEmpty()) {
					DeleteObjectsResponse deleted = s3.deleteObjects(DeleteObjectsRequest.builder()
							.bucket(location.bucket())
							.delete(Delete.builder().objects(objects).quiet(true).build())
							.build());
					if (deleted.hasErrors()) {
						throw new IllegalStateException(
								"S3 prefix batch deletion failed for "
										+ deleted.errors().size() + " object(s)");
					}
				}
			}
			continuation = listRes.isTruncated() ? listRes.nextContinuationToken() : null;
		} while (continuation != null);
		log.info("[storage] deleted folder prefix={} retained={} subtree={}",
				normalized, retainedLogicalKey, retainSubtree);
	}

	@Override
	public void invalidatePublicAsset(UUID assetId) {
		if (assetId == null) {
			throw new IllegalArgumentException("assetId is required for CDN invalidation");
		}
		if (cloudFront == null || !StringUtils.hasText(cloudFrontDistributionId)) {
			log.debug("[storage] CloudFront invalidation not configured assetId={}", assetId);
			return;
		}

		// One wildcard covers source, thumbnail and HLS derivatives while keeping
		// invalidation authority bounded to the immutable UUID namespace.
		String path = "/" + mediaRoot + "/" + assetId + "/*";
		Paths paths = Paths.builder().quantity(1).items(path).build();
		InvalidationBatch batch = InvalidationBatch.builder()
				.paths(paths)
				.callerReference("media-delete-" + assetId + "-" + UUID.randomUUID())
				.build();
		cloudFront.createInvalidation(CreateInvalidationRequest.builder()
				.distributionId(cloudFrontDistributionId)
				.invalidationBatch(batch)
				.build());
		log.info("[storage] CloudFront invalidation submitted assetId={} path={}", assetId, path);
	}
	
	@Override
	public void putFile(Path local, String key, String contentType, String cacheControl) {
		StorageLocation location = locate(key);
		try {
			long size = Files.size(local);
			String effectiveCacheControl = cacheControlFor(location, cacheControl);
			var put = PutObjectRequest.builder()
					.bucket(location.bucket())
					.key(location.key())
					.contentType(contentType)
					.cacheControl(effectiveCacheControl)
					.contentLength(size)
					.build();
			s3.putObject(put, local);
			log.debug("[storage] putFile key={} size={} ct={} cache={}",
					key, size, contentType, effectiveCacheControl);
		} catch (Exception e) {
			throw new RuntimeException("S3 put file failed for key=" + key + " : " + e.getMessage(), e);
		}
	}
	
	@Override
	public void putBytes(byte[] data, String key, String contentType, String cacheControl) {
		StorageLocation location = locate(key);
		try {
			String effectiveCacheControl = cacheControlFor(location, cacheControl);
			var put = PutObjectRequest.builder()
					.bucket(location.bucket())
					.key(location.key())
					.contentType(contentType)
					.cacheControl(effectiveCacheControl)
					.contentLength((long) data.length)
					.build();
			
			s3.putObject(put, RequestBody.fromBytes(data));
			log.debug("[storage] putBytes key={} size={} ct={} cache={}",
					key, data.length, contentType, effectiveCacheControl);
		} catch (Exception e) {
			throw new RuntimeException("S3 putBytes failed for key=" + key + " : " + e.getMessage(), e);
		}
	}
	
	@Override
	public InputStream getObjectStream(String key) {
		StorageLocation location = locate(key);
		var get = GetObjectRequest.builder()
				.bucket(location.bucket())
				.key(location.key())
				.build();
		try {
			// ResponseInputStream<GetObjectResponse> doner - InputStream olarak kullanilabilir,
			return s3.getObject(get);
		} catch (S3Exception e) {
			throw new RuntimeException("S3 getObjectStream failed for key=" + key + " : " + e.awsErrorDetails().errorMessage(), e);
		} catch (Exception e) {
			throw new RuntimeException("S3 getObjectStream failed for key=" + key + " : " + e.getMessage(), e);
		}
	}
	
	@Override
	public void downloadToFile(String key, Path target) {
		downloadToFile(key, target, storageCallTimeout);
	}

	@Override
	public void downloadToFile(String key, Path target, Duration timeout) {
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("download timeout must be positive");
		}
		StorageLocation location = locate(key);
		var get = GetObjectRequest.builder()
		                          .bucket(location.bucket())
		                          .key(location.key())
						  .overrideConfiguration(
								  AwsRequestOverrideConfiguration.builder()
										  .apiCallTimeout(timeout)
										  .apiCallAttemptTimeout(timeout)
										  .build())
		                          .build();
		try {
			Files.createDirectories(target.getParent());
			s3.getObject(get, ResponseTransformer.toFile(target));
			log.debug("[storage] downloaded key={} -> {}", key, target);
		} catch (Exception e) {
			throw new RuntimeException("S3 downloadToFile failed for key=" + key + " : " + e.getMessage(), e);
		}
	}
	
	
	// ---- helper metodlar--------
	private static String stripTrailingSlash(String s) {
		if (!StringUtils.hasText(s)) return "";
		return s.replaceAll("/+$", "");
	}
	
	private static String ensureTrailingSlash(String s) {
		if (!StringUtils.hasText(s)) return "/";
		return s.endsWith("/") ? s : s + "/";
	}

	private String cacheControlFor(StorageLocation location, String requested) {
		return location.privateOrigin() ? requested : boundedPublicCacheControl(requested);
	}

	/**
	 * Enforces a deletion-SLA ceiling independently of individual producers. This
	 * also covers HLS segments whose uploader intentionally requests immutable
	 * year-long caching. CloudFront invalidation remains the fast path; these
	 * bounds are the fail-safe when invalidation is absent or delayed.
	 */
	private String boundedPublicCacheControl(String requested) {
		List<String> otherDirectives = new ArrayList<>();
		long requestedMaxAge = Long.MAX_VALUE;
		long requestedSharedMaxAge = Long.MAX_VALUE;
		boolean hasMaxAge = false;
		boolean hasSharedMaxAge = false;

		if (StringUtils.hasText(requested)) {
			for (String rawDirective : requested.split(",")) {
				String directive = rawDirective.trim();
				if (directive.isEmpty()) continue;
				String lower = directive.toLowerCase(Locale.ROOT);
				if (lower.startsWith("max-age=")) {
					hasMaxAge = true;
					requestedMaxAge = Math.min(requestedMaxAge,
							parseCacheSeconds(directive, publicCacheMaxAgeSeconds));
				} else if (lower.startsWith("s-maxage=")) {
					hasSharedMaxAge = true;
					requestedSharedMaxAge = Math.min(requestedSharedMaxAge,
							parseCacheSeconds(directive, publicSharedCacheMaxAgeSeconds));
				} else if (!"immutable".equals(lower)) {
					otherDirectives.add(directive);
				}
			}
		}

		long effectiveMaxAge = hasMaxAge
				? Math.min(requestedMaxAge, publicCacheMaxAgeSeconds)
				: publicCacheMaxAgeSeconds;
		long effectiveSharedMaxAge = hasSharedMaxAge
				? Math.min(requestedSharedMaxAge, publicSharedCacheMaxAgeSeconds)
				: Math.min(hasMaxAge ? requestedMaxAge : publicSharedCacheMaxAgeSeconds,
						publicSharedCacheMaxAgeSeconds);

		if (otherDirectives.stream().noneMatch(value -> "public".equalsIgnoreCase(value))) {
			otherDirectives.add(0, "public");
		}
		otherDirectives.add("max-age=" + effectiveMaxAge);
		otherDirectives.add("s-maxage=" + effectiveSharedMaxAge);
		return String.join(", ", otherDirectives);
	}

	private long parseCacheSeconds(String directive, long fallback) {
		int equals = directive.indexOf('=');
		if (equals < 0 || equals == directive.length() - 1) return fallback;
		try {
			return Math.max(0L, Long.parseLong(directive.substring(equals + 1).trim()));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private StorageLocation locate(String logicalKey) {
		boolean protectedObject = StorageObjectKeys.isProtected(logicalKey);
		boolean quarantinedObject = StorageObjectKeys.isQuarantined(logicalKey);
		boolean verifiedObject = StorageObjectKeys.isVerified(logicalKey);
		boolean privateOrigin = protectedObject || quarantinedObject || verifiedObject;
		return new StorageLocation(
				privateOrigin ? privateBucket : bucket,
				StorageObjectKeys.physicalKey(logicalKey),
				privateOrigin,
				protectedObject,
				quarantinedObject,
				verifiedObject
		);
	}

	private record StorageLocation(
			String bucket,
			String key,
			boolean privateOrigin,
			boolean protectedObject,
			boolean quarantinedObject,
			boolean verifiedObject
	) {
	}
}
