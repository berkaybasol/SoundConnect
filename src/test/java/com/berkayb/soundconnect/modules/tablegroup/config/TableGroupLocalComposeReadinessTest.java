package com.berkayb.soundconnect.modules.tablegroup.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableGroupLocalComposeReadinessTest {

	@Test
	void localComposeExercisesTheProductionTableGroupReadinessDependencies() throws Exception {
		Path projectRoot = Path.of(System.getProperty("user.dir"));
		YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
		List<PropertySource<?>> compose = loader.load(
				"compose",
				new FileSystemResource(projectRoot.resolve("compose.yaml"))
		);
		List<PropertySource<?>> production = loader.load(
				"production",
				new FileSystemResource(projectRoot.resolve(
						Path.of("src", "main", "resources", "application-prod.yml")))
		);

		assertThat(property(
				compose,
				"services.backend.environment.MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE"
		)).isEqualTo(property(
				production,
				"management.endpoint.health.group.readiness.include"
		));
	}

	private Object property(List<PropertySource<?>> sources, String key) {
		return sources.stream()
				.map(source -> source.getProperty(key))
				.filter(value -> value != null)
				.findFirst()
				.orElse(null);
	}
}
