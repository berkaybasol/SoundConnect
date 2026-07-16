package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.transcode.config.TranscodeProperties;
import com.berkayb.soundconnect.modules.media.transcode.ffmpeg.FfprobeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ImageVariantWorkerBoundaryTest {

	private static final Set<String> SAFE_NATIVE_ENVIRONMENT = Set.of(
			"path", "pathext", "systemroot", "windir",
			"temp", "tmp", "tmpdir", "lang", "lc_all", "tz");

	@TempDir Path tempDir;
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(ImageVariantWorkerConfiguration.class)
			.withBean(MediaImageVariantProperties.class, MediaImageVariantProperties::new);

	@Test
	void disabledWorkerDoesNotCreateNativeImageExecutor() {
		contextRunner
				.withPropertyValues("media.image-variants.worker.enabled=false")
				.run(context -> assertThat(context).doesNotHaveBean("imageVariantExecutor"));
	}

	@Test
	void localWorkerCreatesBoundedImageExecutor() {
		contextRunner
				.withPropertyValues("media.image-variants.worker.enabled=true")
				.run(context -> assertThat(context).hasBean("imageVariantExecutor"));
	}

	@Test
	void everyNativeImageEntryPointUsesTheFailClosedWorkerSwitch() {
		assertConditioned(FfmpegImageThumbnailProcessor.class);
		assertConditioned(ImageThumbnailService.class);
		assertConditioned(ImageVariantBackfillService.class);
		assertConditioned(ImageVariantBackfillFinalizer.class);
		assertConditioned(ImageVariantJobDispatcher.class);
		assertConditioned(ImageThumbnailRequestListener.class);
		assertConditioned(ImageVariantBackfillScheduler.class);
		assertConditioned(ImageVariantWorkerConfiguration.class);
	}

	@Test
	void imageFfmpegChildReceivesOnlyAllowlistedEnvironment() {
		var processor = new FfmpegImageThumbnailProcessor(
				mock(FfprobeService.class),
				new TranscodeProperties(),
				new MediaImageVariantProperties());

		ProcessBuilder builder = processor.processBuilder(List.of("ffmpeg", "-version"), tempDir);

		assertThat(builder.environment().keySet())
				.allMatch(key -> SAFE_NATIVE_ENVIRONMENT.contains(key.toLowerCase(Locale.ROOT)));
	}

	private static void assertConditioned(Class<?> type) {
		assertThat(AnnotatedElementUtils.hasAnnotation(
				type, ConditionalOnImageVariantWorker.class))
				.as(type.getSimpleName())
				.isTrue();
	}
}
