package com.berkayb.soundconnect.modules.media.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class MediaContentSignatureValidatorTest {

	@Test
	void acceptsExpectedSignaturesAndRejectsSpoofedContent() throws Exception {
		assertThat(MediaContentSignatureValidator.matches(
				"image/png",
				stream(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
		)).isTrue();
		assertThat(MediaContentSignatureValidator.matches(
				"video/mp4",
				new ByteArrayInputStream(new byte[]{0, 0, 0, 12, 'f', 't', 'y', 'p'})
		)).isTrue();
		assertThat(MediaContentSignatureValidator.matches(
				"image/png",
				new ByteArrayInputStream("<script>bad</script>".getBytes())
		)).isFalse();
	}

	private ByteArrayInputStream stream(int... values) {
		byte[] bytes = new byte[values.length];
		for (int index = 0; index < values.length; index++) {
			bytes[index] = (byte) values[index];
		}
		return new ByteArrayInputStream(bytes);
	}
}
