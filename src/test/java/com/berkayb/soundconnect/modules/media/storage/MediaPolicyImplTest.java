// src/test/java/com/berkayb/soundconnect/modules/media/storage/MediaPolicyImplTest.java
package com.berkayb.soundconnect.modules.media.storage;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Tag("unit")
class MediaPolicyImplTest {
	
	MediaPolicyImpl policy;
	
	@BeforeEach
	void setUp() {
		policy = new MediaPolicyImpl();
		
		// Limits (küçük değerlerle hızlı test)
		ReflectionTestUtils.setField(policy, "imageMax", 20_000_000L);
		ReflectionTestUtils.setField(policy, "audioMax", 200_000_000L);
		ReflectionTestUtils.setField(policy, "videoMax", 4_000_000_000L);
		
		// Allowed mime CSV’leri
		ReflectionTestUtils.setField(policy, "imageAllowedCsv", "image/png,image/jpeg,image/webp");
		ReflectionTestUtils.setField(policy, "audioAllowedCsv", "audio/mpeg,audio/aac,audio/wav,audio/x-wav,audio/ogg");
		ReflectionTestUtils.setField(policy, "videoAllowedCsv", "video/mp4,video/quicktime,video/x-matroska");
		
		// Paths
		ReflectionTestUtils.setField(policy, "rootDir", "media");
		ReflectionTestUtils.setField(policy, "hlsDir", "hls");
		ReflectionTestUtils.setField(policy, "sourceBasename", "source");
		
		// @PostConstruct’i manuel çağır
		ReflectionTestUtils.invokeMethod(policy, "init");
	}
	
	@Test
	void validate_image_ok_and_size_limit() {
		// OK
		assertThatCode(() ->
				               policy.validate(MediaKind.IMAGE, "image/png", 1024)
		).doesNotThrowAnyException();
		
		// Aşırı büyük: size limit
		assertThatThrownBy(() ->
				                   policy.validate(MediaKind.IMAGE, "image/png", 21_000_000L)
		).isInstanceOf(SoundConnectException.class);
		
		// Geçersiz mime
		assertThatThrownBy(() ->
				                   policy.validate(MediaKind.IMAGE, "image/gif", 1000)
		).isInstanceOf(SoundConnectException.class);
	}
	
	@Test
	void validate_audio_ok_and_bad_inputs_throw() {
		assertThatCode(() ->
				               policy.validate(MediaKind.AUDIO, "audio/mpeg", 123_456L)
		).doesNotThrowAnyException();
		
		// Null/blank ve non-positive boyutlar fail
		assertThatThrownBy(() ->
				                   policy.validate(null, "audio/mpeg", 100)
		).isInstanceOf(SoundConnectException.class);
		
		assertThatThrownBy(() ->
				                   policy.validate(MediaKind.AUDIO, "", 100)
		).isInstanceOf(SoundConnectException.class);
		
		assertThatThrownBy(() ->
				                   policy.validate(MediaKind.AUDIO, "audio/mpeg", 0)
		).isInstanceOf(SoundConnectException.class);
	}
	
	@Test
	void validate_video_mp4_within_limit_ok() {
		assertThatCode(() ->
				               policy.validate(MediaKind.VIDEO, "video/mp4", 10_000_000L)
		).doesNotThrowAnyException();
	}
	
	@Test
	void buildSourceKey_uses_uuid_and_sanitized_extension() {
		UUID id = UUID.randomUUID();
		
		// Türkçe/özel karakterli isim + jpeg → jpg normalize edilir
		String k1 = policy.buildSourceKey(id, "çılgın fotoğraf.JPEG");
		assertThat(k1).isEqualTo("media/" + id + "/source.jpg");
		
		// Uzantı yoksa .dat
		String k2 = policy.buildSourceKey(id, "dosya");
		assertThat(k2).isEqualTo("media/" + id + "/source.dat");
		
		// Boş isim -> .dat
		String k3 = policy.buildSourceKey(id, "");
		assertThat(k3).isEqualTo("media/" + id + "/source.dat");
		
		// Null id -> hata
		assertThatThrownBy(() ->
				                   policy.buildSourceKey(null, "video.mp4")
		).isInstanceOf(SoundConnectException.class);
	}
	
	@Test
	void buildHlsPrefix_ok_and_nullId_throws() {
		UUID id = UUID.randomUUID();
		String prefix = policy.buildHlsPrefix(id);
		assertThat(prefix).isEqualTo("media/" + id + "/hls");
		
		assertThatThrownBy(() ->
				                   policy.buildHlsPrefix(null)
		).isInstanceOf(SoundConnectException.class);
	}
}