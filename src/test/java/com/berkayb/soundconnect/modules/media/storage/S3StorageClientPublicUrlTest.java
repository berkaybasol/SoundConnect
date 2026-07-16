// src/test/java/com/berkayb/soundconnect/modules/media/storage/S3StorageClientPublicUrlTest.java
package com.berkayb.soundconnect.modules.media.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.model.CreateInvalidationRequest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Tag("unit")
class S3StorageClientPublicUrlTest {
	
	S3StorageClient client;
	
	@BeforeEach
	void setup() {
		client = new S3StorageClient();
		// init() çağırmıyoruz; sadece publicUrl karar mantığını test edeceğiz.
	}
	
	@Test
	void publicUrl_prefersCdn_whenConfigured_removesTrailingSlash() {
		ReflectionTestUtils.setField(client, "bucket", "sound-bucket");
		ReflectionTestUtils.setField(client, "region", "eu-central-1");
		ReflectionTestUtils.setField(client, "endpoint", "");
		ReflectionTestUtils.setField(client, "cdnBaseUrl", "https://cdn.example.com/"); // trailing slash
		String key = "media/123/source.mp4";
		
		String url = client.publicUrl(key);
		
		assertThat(url).isEqualTo("https://cdn.example.com/" + key);
	}
	
	@Test
	void publicUrl_usesCustomEndpoint_pathStyle_whenNoCdn() {
		ReflectionTestUtils.setField(client, "bucket", "sound-bucket");
		ReflectionTestUtils.setField(client, "region", "eu-central-1");
		ReflectionTestUtils.setField(client, "endpoint", "https://r2.example.net"); // no trailing slash
		ReflectionTestUtils.setField(client, "cdnBaseUrl", "");
		String key = "media/abc/hls/master.m3u8";
		
		String url = client.publicUrl(key);
		
		assertThat(url).isEqualTo("https://r2.example.net/sound-bucket/" + key);
	}
	
	@Test
	void publicUrl_defaultsToAwsVirtualHosted_whenNoCdnAndNoCustomEndpoint() {
		ReflectionTestUtils.setField(client, "bucket", "sound-bucket");
		ReflectionTestUtils.setField(client, "region", "us-east-1");
		ReflectionTestUtils.setField(client, "endpoint", "");
		ReflectionTestUtils.setField(client, "cdnBaseUrl", "");
		String key = "media/z/source.dat";
		
		String url = client.publicUrl(key);
		
		assertThat(url).isEqualTo("https://sound-bucket.s3.us-east-1.amazonaws.com/" + key);
	}
	
	@Test
	void publicUrl_withCdn_withoutTrailingSlash_keepsSingleSlashJoin() {
		ReflectionTestUtils.setField(client, "bucket", "b");
		ReflectionTestUtils.setField(client, "region", "r");
		ReflectionTestUtils.setField(client, "endpoint", "");
		ReflectionTestUtils.setField(client, "cdnBaseUrl", "https://cdn.foo.bar"); // no slash
		String key = "media/k";
		
		String url = client.publicUrl(key);
		
		assertThat(url).isEqualTo("https://cdn.foo.bar/" + key);
	}

	@AfterEach
	void closeSdkClients() {
		Object s3 = ReflectionTestUtils.getField(client, "s3");
		if (s3 instanceof S3Client s3Client) {
			s3Client.close();
		}
		Object presigner = ReflectionTestUtils.getField(client, "presigner");
		if (presigner instanceof software.amazon.awssdk.services.s3.presigner.S3Presigner s3Presigner) {
			s3Presigner.close();
		}
	}

	@Test
	void publicUrl_rejectsProtectedLogicalKeys() {
		ReflectionTestUtils.setField(client, "bucket", "public-bucket");
		ReflectionTestUtils.setField(client, "privateBucket", "private-bucket");
		ReflectionTestUtils.setField(client, "cdnBaseUrl", "https://cdn.example.com");

		assertThatThrownBy(() -> client.publicUrl("protected/media/id/source.png"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("do not have a public URL");
	}

	@Test
	void publicUrl_rejectsQuarantineLogicalKeys() {
		ReflectionTestUtils.setField(client, "bucket", "public-bucket");
		ReflectionTestUtils.setField(client, "privateBucket", "private-bucket");
		ReflectionTestUtils.setField(client, "cdnBaseUrl", "https://cdn.example.com");

		assertThatThrownBy(() -> client.publicUrl("quarantine/media/id/source.png"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Private-origin");
	}

	@Test
	void immutableSnapshotUsesEtagAndPromotionCopiesOnlyVerifiedBytesToPublicBucket() {
		S3Client s3 = mock(S3Client.class);
		ReflectionTestUtils.setField(client, "bucket", "public-bucket");
		ReflectionTestUtils.setField(client, "privateBucket", "private-bucket");
		ReflectionTestUtils.setField(client, "s3", s3);
		ReflectionTestUtils.setField(client, "aclPublicReadOnPut", false);
		String quarantineKey = "quarantine/media/id/source.png";
		String verifiedKey = "verified/media/id/source.png";
		String publicKey = "media/id/source.png";

		client.copyUploadToImmutable(quarantineKey, verifiedKey, "\"etag-v1\"");
		client.promoteVerifiedObject(
				verifiedKey, publicKey, "image/png", "public, max-age=31536000, immutable",
				"\"verified-etag-v1\"");

		ArgumentCaptor<CopyObjectRequest> requestCaptor = ArgumentCaptor.forClass(CopyObjectRequest.class);
		verify(s3, times(2)).copyObject(requestCaptor.capture());
		CopyObjectRequest snapshotRequest = requestCaptor.getAllValues().get(0);
		assertThat(snapshotRequest.copySource())
				.isEqualTo("private-bucket/quarantine%2Fmedia%2Fid%2Fsource.png");
		assertThat(snapshotRequest.copySourceIfMatch()).isEqualTo("\"etag-v1\"");
		assertThat(snapshotRequest.destinationBucket()).isEqualTo("private-bucket");
		assertThat(snapshotRequest.destinationKey()).isEqualTo(verifiedKey);
		assertThat(snapshotRequest.metadataDirective()).isEqualTo(MetadataDirective.COPY);

		CopyObjectRequest promotionRequest = requestCaptor.getAllValues().get(1);
		assertThat(promotionRequest.copySource())
				.isEqualTo("private-bucket/verified%2Fmedia%2Fid%2Fsource.png");
		assertThat(promotionRequest.copySourceIfMatch()).isEqualTo("\"verified-etag-v1\"");
		assertThat(promotionRequest.destinationBucket()).isEqualTo("public-bucket");
		assertThat(promotionRequest.destinationKey()).isEqualTo(publicKey);
		assertThat(promotionRequest.metadataDirective()).isEqualTo(MetadataDirective.REPLACE);
		assertThat(promotionRequest.contentType()).isEqualTo("image/png");
		assertThat(promotionRequest.cacheControl())
				.isEqualTo("public, max-age=300, s-maxage=3600");
	}

	@Test
	void invalidationIsOneAssetScopedWildcardAndNeverAcceptsCallerPaths() {
		CloudFrontClient cloudFront = mock(CloudFrontClient.class);
		ReflectionTestUtils.setField(client, "cloudFront", cloudFront);
		ReflectionTestUtils.setField(client, "cloudFrontDistributionId", "E1234567890");
		ReflectionTestUtils.setField(client, "mediaRoot", "media");
		UUID assetId = UUID.randomUUID();

		client.invalidatePublicAsset(assetId);

		ArgumentCaptor<CreateInvalidationRequest> captor =
				ArgumentCaptor.forClass(CreateInvalidationRequest.class);
		verify(cloudFront).createInvalidation(captor.capture());
		CreateInvalidationRequest request = captor.getValue();
		assertThat(request.distributionId()).isEqualTo("E1234567890");
		assertThat(request.invalidationBatch().paths().quantity()).isEqualTo(1);
		assertThat(request.invalidationBatch().paths().items())
				.containsExactly("/media/" + assetId + "/*");
		assertThat(request.invalidationBatch().callerReference())
				.startsWith("media-delete-" + assetId + "-");
	}

	@Test
	void invalidationWithoutDistributionIsLocalSafeNoOp() {
		ReflectionTestUtils.setField(client, "cloudFront", null);
		ReflectionTestUtils.setField(client, "cloudFrontDistributionId", "");

		client.invalidatePublicAsset(UUID.randomUUID());

		assertThatThrownBy(() -> client.invalidatePublicAsset(null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void invalidationRootRejectsWildcardOrTraversalConfiguration() {
		ReflectionTestUtils.setField(client, "mediaRoot", "media/*");
		assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(client, "validateMediaRoot"))
				.isInstanceOf(IllegalStateException.class);

		ReflectionTestUtils.setField(client, "mediaRoot", "../media");
		assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(client, "validateMediaRoot"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void publicCachePolicyCapsYearLongObjectsButPreservesShortManifestTtl() {
		String immutable = ReflectionTestUtils.invokeMethod(
				client, "boundedPublicCacheControl", "public, max-age=31536000, immutable");
		String manifest = ReflectionTestUtils.invokeMethod(
				client, "boundedPublicCacheControl", "public, max-age=30, s-maxage=60");

		assertThat(immutable).isEqualTo("public, max-age=300, s-maxage=3600");
		assertThat(manifest).isEqualTo("public, max-age=30, s-maxage=60");
	}

	@Test
	void promotionRejectsArbitraryOrPrivateTargetsBeforeCallingS3() {
		S3Client s3 = mock(S3Client.class);
		ReflectionTestUtils.setField(client, "bucket", "public-bucket");
		ReflectionTestUtils.setField(client, "privateBucket", "private-bucket");
		ReflectionTestUtils.setField(client, "s3", s3);

		assertThatThrownBy(() -> client.copyUploadToImmutable(
				"quarantine/media/id/source.png", "verified/media/other/source.png", "etag"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client.promoteVerifiedObject(
				"quarantine/media/id/source.png", "media/id/source.png",
				"image/png", "public, max-age=31536000, immutable", "etag"))
				.isInstanceOf(IllegalArgumentException.class);
		verify(s3, never()).copyObject(org.mockito.ArgumentMatchers.any(CopyObjectRequest.class));
	}

	@Test
	void quarantinePresignedPutTargetsPrivateBucketAndNeverPublicBucket() {
		ReflectionTestUtils.setField(client, "bucket", "public-bucket");
		ReflectionTestUtils.setField(client, "privateBucket", "private-bucket");
		ReflectionTestUtils.setField(client, "region", "eu-central-1");
		ReflectionTestUtils.setField(client, "accessKey", "test-access");
		ReflectionTestUtils.setField(client, "secretKey", "test-secret");
		ReflectionTestUtils.setField(client, "endpoint", "");
		ReflectionTestUtils.setField(client, "cdnBaseUrl", "https://cdn.example.com");
		ReflectionTestUtils.setField(client, "pathStyleAccess", false);
		ReflectionTestUtils.setField(client, "uploadPresignExpirySeconds", 900);
		ReflectionTestUtils.setField(client, "downloadPresignExpirySeconds", 300);
		ReflectionTestUtils.setField(client, "aclPublicReadOnPut", false);
		client.init();

		String signedUrl = client.createPresignedPutUrl(
				"quarantine/media/id/source.png", "image/png", 128L);

		assertThat(signedUrl).contains("private-bucket.s3.eu-central-1.amazonaws.com");
		assertThat(signedUrl).doesNotContain("public-bucket");
	}

	@Test
	void credentialsProvider_usesDefaultChainWhenStaticKeysAreAbsent() {
		ReflectionTestUtils.setField(client, "accessKey", "");
		ReflectionTestUtils.setField(client, "secretKey", "");

		Object provider = ReflectionTestUtils.invokeMethod(client, "credentialsProvider");

		assertThat(provider).isInstanceOf(DefaultCredentialsProvider.class);
	}

	@Test
	void credentialsProvider_usesStaticProviderOnlyWhenBothKeysExist() {
		ReflectionTestUtils.setField(client, "accessKey", "access");
		ReflectionTestUtils.setField(client, "secretKey", "secret");

		Object provider = ReflectionTestUtils.invokeMethod(client, "credentialsProvider");

		assertThat(provider).isInstanceOf(StaticCredentialsProvider.class);
	}

	@Test
	void credentialsProvider_rejectsPartialStaticCredentialConfiguration() {
		ReflectionTestUtils.setField(client, "accessKey", "access");
		ReflectionTestUtils.setField(client, "secretKey", "");

		assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(client, "credentialsProvider"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("configured together");
	}
}
