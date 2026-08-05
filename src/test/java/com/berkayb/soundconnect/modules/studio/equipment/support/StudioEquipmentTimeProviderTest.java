package com.berkayb.soundconnect.modules.studio.equipment.support;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class StudioEquipmentTimeProviderTest {

    @Test
    void invalidLegacyZoneFallsBackToTheStudioDefault() {
        StudioEquipmentTimeProvider provider = new StudioEquipmentTimeProvider(
                Clock.fixed(Instant.parse("2026-08-03T22:30:00Z"), ZoneOffset.UTC)
        );

        assertThat(provider.today("Not/A_Real_Zone"))
                .isEqualTo(LocalDate.of(2026, 8, 4));
    }
}
