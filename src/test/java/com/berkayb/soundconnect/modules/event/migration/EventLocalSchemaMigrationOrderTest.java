package com.berkayb.soundconnect.modules.event.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventLocalSchemaMigrationOrderTest {

    @Test
    void localBootstrapIncludesEveryEventPrivacyMigrationOnceInDependencyOrder() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        String script = Files.readString(root.resolve("scripts/dev.ps1"));
        int start = script.indexOf("$LocalSchemaMigrations = @(");
        assertThat(start).isNotNegative();
        int end = script.indexOf("\n)", start);
        assertThat(end).isGreaterThan(start);
        String registry = script.substring(start, end);
        List<String> migrations = List.of(
                "2026-09-04-event-performer-consent.sql",
                "2026-09-05-musician-calendar.sql",
                "2026-09-05-performer-calendar-opt-in.sql",
                "2026-09-05-event-profile-visibility-consent.sql",
                "2026-09-05-reciprocal-musician-events.sql",
                "2026-09-06-event-profile-publications.sql");

        int previous = -1;
        for (String migration : migrations) {
            assertThat(registry).as("registered migration %s", migration).containsOnlyOnce(migration);
            assertThat(registry.indexOf(migration)).as("dependency order for %s", migration).isGreaterThan(previous);
            assertThat(root.resolve("scripts/db").resolve(migration)).isRegularFile();
            previous = registry.indexOf(migration);
        }
    }
}
