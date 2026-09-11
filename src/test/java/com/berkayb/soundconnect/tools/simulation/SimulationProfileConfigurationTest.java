package com.berkayb.soundconnect.tools.simulation;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationProfileConfigurationTest {

	@Test
	void profileIsInertByDefaultAndUsesOnlyReservedAdminRecipients() throws Exception {
		var loaded = new YamlPropertySourceLoader().load(
				"simulation",
				new ClassPathResource("application-simulation.yml"));
		MutablePropertySources sources = new MutablePropertySources();
		loaded.forEach(sources::addLast);
		PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);

		assertThat(resolver.getProperty("app.simulation.enabled", Boolean.class)).isFalse();
		assertThat(resolver.getProperty("app.simulation.destructive-reset-acknowledged", Boolean.class))
				.isFalse();
		assertThat(resolver.getProperty("app.simulation.max-accounts", Integer.class)).isEqualTo(50);
		assertThat(resolver.getProperty("app.simulation.mode")).isEqualTo("PAUSE");
		assertThat(resolver.getProperty("app.simulation.expected-postgresql-database"))
				.isEmpty();
		assertThat(resolver.getProperty("app.security.auth-rate-limit.enabled", Boolean.class)).isFalse();
		assertThat(resolver.getProperty("soundconnect.admin-notifications.venue-application-emails"))
				.endsWith(".invalid");
		assertThat(resolver.getProperty("soundconnect.admin-notifications.studio-application-emails"))
				.endsWith(".invalid");
	}
}
