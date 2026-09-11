package com.berkayb.soundconnect.tools.simulation.seed.media;

import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Loads the small, version-controlled, copyright-safe simulation fixtures. */
final class SimulationMediaFixtures {

	static final String IMAGE_RESOURCE = "simulation/media/seed-image.png.b64";
	static final String AUDIO_RESOURCE = "simulation/media/seed-audio.wav.b64";

	private final Fixture image;
	private final Fixture audio;

	SimulationMediaFixtures() {
		this.image = load(IMAGE_RESOURCE, MediaKind.IMAGE, "image/png", "simulation-image.png");
		this.audio = load(AUDIO_RESOURCE, MediaKind.AUDIO, "audio/wav", "simulation-audio.wav");
	}

	Fixture image() {
		return image;
	}

	Fixture audio() {
		return audio;
	}

	private static Fixture load(
			String resourceName,
			MediaKind kind,
			String contentType,
			String fileName
	) {
		ClassPathResource resource = new ClassPathResource(resourceName);
		try (InputStream input = resource.getInputStream()) {
			String encoded = new String(input.readAllBytes(), StandardCharsets.US_ASCII)
					.replaceAll("\\s+", "");
			byte[] bytes = Base64.getDecoder().decode(encoded);
			if (bytes.length == 0) {
				throw new IllegalStateException("Simulation media fixture is empty: " + resourceName);
			}
			return new Fixture(kind, contentType, fileName, bytes);
		} catch (IOException | IllegalArgumentException exception) {
			throw new IllegalStateException(
					"Unable to load simulation media fixture: " + resourceName, exception);
		}
	}

	record Fixture(MediaKind kind, String contentType, String fileName, byte[] bytes) {
		Fixture {
			bytes = bytes.clone();
		}

		@Override
		public byte[] bytes() {
			return bytes.clone();
		}
	}
}
