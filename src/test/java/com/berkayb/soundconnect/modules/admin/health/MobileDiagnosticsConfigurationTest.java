package com.berkayb.soundconnect.modules.admin.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MobileDiagnosticsConfigurationTest {
    @Test void localProfileKeepsLocalReportsAndProductionAcceptsProductionReportsByDefault() throws IOException {
        assertThat(bound(false, null).getEnvironment()).isEqualTo("local");
        assertThat(bound(true, null).getEnvironment()).isEqualTo("production");
    }

    @Test void explicitEnvironmentIsSharedByBaseAndProductionProfile() throws IOException {
        assertThat(bound(false, "staging").getEnvironment()).isEqualTo("staging");
        assertThat(bound(true, "staging").getEnvironment()).isEqualTo("staging");
        assertThat(bound(true, "local").getEnvironment()).isEqualTo("local");
    }

    @Test void documentedComposeWorkerMarkerEnvironmentBindsThroughBaseConfiguration() throws IOException {
        var environment = new MockEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("compose",
                Map.of("APP_SYSTEM_HEALTH_MEDIA_WORKER_HEALTH_FILE", "/health/media-worker.ready")));
        new YamlPropertySourceLoader().load("base", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        assertThat(Binder.get(environment).bind("app.system-health", SystemHealthProperties.class)
                .get().getMediaWorkerHealthFile()).isEqualTo("/health/media-worker.ready");
    }

    private MobileDiagnosticsProperties bound(boolean production, String override) throws IOException {
        var environment = new MockEnvironment();
        var loader = new YamlPropertySourceLoader();
        if (override != null) environment.setProperty("SOUNDCONNECT_ENVIRONMENT", override);
        if (production) loader.load("production", new ClassPathResource("application-prod.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        loader.load("base", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return Binder.get(environment).bind("app.diagnostics.mobile", MobileDiagnosticsProperties.class).get();
    }
}
