package com.berkayb.soundconnect.tools.simulation.world;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Strict classpath loader; it performs validation before returning any world description. */
public final class SimulationWorldManifestLoader {
	public static final String DEFAULT_RESOURCE = "simulation/world-v1.json";

	private final ObjectReader reader;
	private final SimulationWorldManifestValidator validator;

	public SimulationWorldManifestLoader(ObjectMapper objectMapper) {
		this(objectMapper, new SimulationWorldManifestValidator(objectMapper));
	}

	public SimulationWorldManifestLoader(
			ObjectMapper objectMapper,
			SimulationWorldManifestValidator validator
	) {
		Objects.requireNonNull(objectMapper, "objectMapper");
		this.validator = Objects.requireNonNull(validator, "validator");
		this.reader = objectMapper.readerFor(SimulationWorldManifest.class)
				.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
	}

	public SimulationWorldManifest loadDefault() {
		return load(new ClassPathResource(DEFAULT_RESOURCE));
	}

	public SimulationWorldManifest load(Resource resource) {
		Objects.requireNonNull(resource, "resource");
		try (InputStream input = resource.getInputStream()) {
			return validator.validate(reader.readValue(input));
		} catch (SimulationWorldManifestException exception) {
			throw exception;
		} catch (IOException | RuntimeException exception) {
			throw new SimulationWorldManifestException(
					"Unable to load simulation world manifest: " + resource.getDescription(), exception);
		}
	}
}
