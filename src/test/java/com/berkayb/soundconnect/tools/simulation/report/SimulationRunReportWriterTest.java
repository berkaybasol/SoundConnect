package com.berkayb.soundconnect.tools.simulation.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationRunReportWriterTest {

	@TempDir
	Path temporaryDirectory;

	@Test
	void writesJsonAndHumanTimelineWithoutDatabaseIdentifiers() throws Exception {
		Clock clock = Clock.fixed(Instant.parse("2026-09-11T10:15:30Z"), ZoneOffset.UTC);
		SimulationRunLedger ledger = new SimulationRunLedger("world-v1", 20260911L, clock);
		ledger.plan("accounts", 50);
		ledger.succeeded("accounts", "REGISTER", "musician-01", null, "ACTIVE");
		ledger.skipped("profiles", "UPDATE", "listener-ghost-01", null, "ghost profile");

		SimulationRunReportWriter.WrittenReports reports = new SimulationRunReportWriter(new ObjectMapper())
				.write(temporaryDirectory, ledger.snapshot());

		assertThat(reports.json()).exists();
		assertThat(reports.markdown()).exists();
		assertThat(Files.readString(reports.markdown()))
				.contains("SoundConnect simulation run", "musician-01", "listener-ghost-01", "accounts", "50");
		assertThat(Files.readString(reports.json())).contains("\"worldId\" : \"world-v1\"");
	}
}
