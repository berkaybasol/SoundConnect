package com.berkayb.soundconnect.modules.media.storage;

import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * StorageClient'in S3 uyarlamasi.
 * Presigned PUT ile istemci dosyayi dogrudan S3'e yukler
 * Public URL uretimi CloudFront (cdnBaseUrl) uzerinden calisir.(OAC var)
 * Silme islemleri tek obje veya prefix(HLS klasoru) bazinda yapilir
 */
@Component
@Slf4j
public class S3StorageClient implements StorageClient{
	
	// s3 bucket name'i yml'den cekiyoruz
	@Value("${cloud.storage.bucket}")
	private String bucket;
	
	// bucket'a bagli region yml'den cekiyoruz
	@Value("${cloud.storage.region}")
	private String region;
	
	// s3 acces key yml'den cekiyoruz
	@Value("${cloud.storage.accessKey}")
	private String accessKey;
	
	@Value("${cloud.storage.secretKey}")
	private String secretKey;
	
	@Value("${cloud.storage.endpoint:}") // AWS S3 icin bos birakiyoruz R2'ye gecersek kullanilacak.
	private String endpoint;
	
	@Value("${cloud.storage.cdnBaseUrl:}") // CloudFront base URL
	private String cdnBaseUrl;
	
	@Value("${cloud.storage.pathStyleAccess:false}") // AWS false R2 true olcak
	private boolean pathStyleAccess;
	
	@Value("${cloud.storage.presign.expirySeconds:900}") // 15dk
	private int presignExpirySeconds;
	
	@Value("${cloud.storage.aclPublicReadOnPut:false}") // OAC var o yuzden false
	private boolean aclPublicReadOnPut;
	
	// s3'e gercek cagrilari yapan client
	private S3Client s3;
	// presigned url ureten client
	private S3Presigner presigner;
	
	
	// yapilandirmaya gore client'lari kur
	@PostConstruct
	void  init() {
		// kimlik bilgileri (env'de tanimladik)
		var credentials = StaticCredentialsProvider.create(
				AwsBasicCredentials.create(accessKey, secretKey)
		);
		
		// S3 Client config (timeout + path-style)
		var s3Cfg = S3Configuration.builder()
				.pathStyleAccessEnabled(pathStyleAccess) // R2' de true gerekebilir.
				.build();
		
		// timeout gibi client genel ayarlari
		var override = ClientOverrideConfiguration.builder()
		        .apiCallTimeout(Duration.ofSeconds(30)) // tum cagri icin ust sinir
		        .apiCallAttemptTimeout(Duration.ofSeconds(30)) // tek attempt ust siniri
		        .build();
		
		// S3Client builder'i HTTP client + cred + overrides + s3 config
		var s3Builder = S3Client.builder()
				.httpClient(UrlConnectionHttpClient.create())
				.credentialsProvider(credentials)
				.overrideConfiguration(override)
				.serviceConfiguration(s3Cfg);
		
		// presigner builder'i imzali url uretimi icin
		var presignerBuilder = S3Presigner.builder()
				.credentialsProvider(credentials);
		
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
		
		log.info("[storage] S3StorageClient initialized bucket={} region={} endpoint={} pathStyle={} cdnBaseUrl={} aclPublicReadOnPut={}",
		         bucket, region, endpoint, pathStyleAccess, cdnBaseUrl, aclPublicReadOnPut);
	}
	
	// PRESIGNED PUT: istemcinin dogrudan S3'e yuklemesi icin imzali URL
	@Override
	public String createPresignedPutUrl(String objectKey, String mimeType) {
		// put sirasinda objeye eklencek metadata/basliklar
		var putReqBuilder = PutObjectRequest.builder()
				.bucket(bucket) // hedef bucket
				.key(objectKey) // object key orn: media/{id}/source.mp4
				.contentType(mimeType);  // icerik turu (cache/proxy davranisi icin onemli)
		
		// OAC (Origin Access Control) kullaniyoruz o yuzden public acl vermiyoruz false birakiyoruz
		if (aclPublicReadOnPut) {
			putReqBuilder.acl(ObjectCannedACL.PUBLIC_READ);
		}
		
		var putReq = putReqBuilder.build();
		
		// presigned url suresi ( guvenlik icin kisa tutcaz)
		var presignReq = PutObjectPresignRequest.builder()
				.putObjectRequest(putReq)
				.signatureDuration(Duration.ofSeconds(presignExpirySeconds))
				.build();
		
		// url uret ve dondur
		URL url = presigner.presignPutObject(presignReq).url();
		return url.toString();
	}
	
	
	// PUBLIC URL: Playback/thumnail gosterimi icin erisim adresi
	@Override
	public String publicUrl(String objectKey) {
		// cloudfront tanimliysa her zaman cdn'i kullan (oac senaryosunda onerilen yol)
		if (StringUtils.hasText(cdnBaseUrl)) {
			return stripTrailingSlash(cdnBaseUrl) + "/" + objectKey;
		}
		// CDN yoksa ve custom endpoint (R2/MinIO) kullanirsak path-style ile olustturuoruz
		if (StringUtils.hasText(endpoint)) {
			// pathStyleAccess = false olsa cogu S3-compatible'da guvenli yol
			return stripTrailingSlash(endpoint) + "/" + bucket + "/" + objectKey;
		}
		// AWS S3 default virtual-hosted style
		return "https://" + bucket + ".s3." + region + ".amazonaws.com/" + objectKey;
	}
	
	// tek obje silme
	@Override
	public void deleteObject(String objectKey) {
		s3.deleteObject(DeleteObjectRequest.builder()
				                .bucket(bucket)
				                .key(objectKey)
				                .build());
		log.info("[storage] deleted object key={}", objectKey);
	}
	
	// prefix altindaki tum objeleri silme (orn HLS klasoru)
	@Override
	public void deleteFolder(String prefix) {
		// s3'de klasor yok; prefix ile listleriz sonra tek tek sileriz
		String continuation = null;
		var normalized = ensureTrailingSlash(prefix);
		do {
			var listReq = ListObjectsV2Request.builder()
					.bucket(bucket)
					.prefix(normalized)
					.continuationToken(continuation)
					.build();
			
			var listRes = s3.listObjectsV2(listReq);
			
			if (listRes.hasContents()) {
				for (var obj : listRes.contents()) {
					try {
						s3.deleteObject(DeleteObjectRequest.builder()
						                         .bucket(bucket)
								                .key(obj.key())
								                .build());
					} catch (Exception e) {
						log.warn("[storage] deleteFolder failed key={} err={}", obj.key(), e.getMessage());
					}
				}
			}
			continuation = listRes.isTruncated() ? listRes.nextContinuationToken() : null;
		} while (continuation != null);
		log.info("[storage] deleted folder prefix={}", normalized);
	}
	
	@Override
	public void putFile(Path local, String key, String contentType, String cacheControl) {
		try {
			long size = Files.size(local);
			var put = PutObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.contentType(contentType)
					.cacheControl(cacheControl)
					.contentLength(size)
					.build();
			s3.putObject(put, local);
			log.debug("[storage] putFile key={} size={} ct={} cache={}", key, size, contentType, cacheControl);
		} catch (Exception e) {
			throw new RuntimeException("S3 put file failed for key=" + key + " : " + e.getMessage(), e);
		}
	}
	
	@Override
	public void putBytes(byte[] data, String key, String contentType, String cacheControl) {
		try {
			var put = PutObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.contentType(contentType)
					.cacheControl(cacheControl)
					.contentLength((long) data.length)
					.build();
			
			s3.putObject(put, RequestBody.fromBytes(data));
			log.debug("[storage] putBytes key={} size={} ct={} cache={}", key, data.length, contentType, cacheControl);
		} catch (Exception e) {
			throw new RuntimeException("S3 putBytes failed for key=" + key + " : " + e.getMessage(), e);
		}
	}
	
	@Override
	public InputStream getObjectStream(String key) {
		var get = GetObjectRequest.builder()
				.bucket(bucket)
				.key(key)
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
		var get = GetObjectRequest.builder()
		                          .bucket(bucket)
		                          .key(key)
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
}