package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BandMemberTitleMigrationOrderTest {
    @Test
    void localBootstrapRegistersAdditiveTitleMigrationOnce() throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        String dev = Files.readString(root.resolve("scripts/dev.ps1"));
        int start = dev.indexOf("$LocalSchemaMigrations = @(");
        String registry = dev.substring(start, dev.indexOf("\n)", start));
        String filename = "2026-09-06-band-member-titles.sql";
        assertThat(registry).containsOnlyOnce(filename);
        assertThat(registry.indexOf(filename))
                .isGreaterThan(registry.indexOf("2026-09-06-event-profile-publications.sql"));
        String sql = Files.readString(root.resolve("scripts/db").resolve(filename));
        assertThat(sql).contains("BEGIN;", "COMMIT;", "ADD COLUMN IF NOT EXISTS member_title",
                "ADD COLUMN IF NOT EXISTS title_version", "CHECK (title_version >= 0)",
                "ON CONFLICT DO NOTHING", "lock_timeout", "statement_timeout");
        assertThat(sql).doesNotContain("DROP TABLE", "TRUNCATE", "DELETE FROM", "UPDATE tbl_event",
                "SET band_role", "SET status");
    }
}
