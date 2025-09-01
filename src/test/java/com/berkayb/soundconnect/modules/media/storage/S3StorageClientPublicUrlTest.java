// src/test/java/com/berkayb/soundconnect/modules/media/storage/S3StorageClientPublicUrlTest.java
package com.berkayb.soundconnect.modules.media.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

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
}