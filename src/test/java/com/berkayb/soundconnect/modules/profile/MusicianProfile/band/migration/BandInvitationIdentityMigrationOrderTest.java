package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.migration;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class BandInvitationIdentityMigrationOrderTest {
    @Test
    void migrationIsRegisteredAfterMembershipSchemaAndNeverRewritesHistoricalNotifications() throws Exception {
        Path root=Path.of(System.getProperty("user.dir"));
        String dev=Files.readString(root.resolve("scripts/dev.ps1"));
        int start=dev.indexOf("$LocalSchemaMigrations = @(");
        String registry=dev.substring(start,dev.indexOf("\n)",start));
        String name="2026-09-07-band-invitation-identity.sql";
        assertThat(registry).containsOnlyOnce(name);
        assertThat(registry.indexOf(name)).isGreaterThan(registry.indexOf("2026-09-06-band-member-titles.sql"));
        String sql=Files.readString(root.resolve("scripts/db").resolve(name));
        assertThat(sql).contains("BEGIN;","COMMIT;","ADD COLUMN IF NOT EXISTS invitation_id uuid",
            "status = 'PENDING' AND invitation_id IS NULL","gen_random_uuid()","lock_timeout","statement_timeout");
        assertThat(sql).doesNotContain("DROP TABLE","DELETE FROM","UPDATE tbl_notification","SET status","SET title_version");
    }
}
