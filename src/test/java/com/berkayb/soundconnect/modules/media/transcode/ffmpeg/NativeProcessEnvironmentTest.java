package com.berkayb.soundconnect.modules.media.transcode.ffmpeg;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativeProcessEnvironmentTest {

	@Test
	void stripsApplicationSecretsButRetainsExecutableAndLocaleBasics() {
		ProcessBuilder builder = new ProcessBuilder("ffmpeg", "-version");
		builder.environment().clear();
		builder.environment().put("PATH", "/usr/bin");
		builder.environment().put("LANG", "C.UTF-8");
		builder.environment().put("S3_SECRET_KEY", "must-not-leak");
		builder.environment().put("SPRING_DATASOURCE_PASSWORD", "must-not-leak");

		NativeProcessEnvironment.sanitize(builder);

		assertThat(builder.environment())
				.containsEntry("PATH", "/usr/bin")
				.containsEntry("LANG", "C.UTF-8")
				.doesNotContainKeys("S3_SECRET_KEY", "SPRING_DATASOURCE_PASSWORD");
	}
}
