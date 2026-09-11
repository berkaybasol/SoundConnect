package com.berkayb.soundconnect.tools.simulation.report;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Writes one machine-readable report and one compact human summary atomically. */
public final class SimulationRunReportWriter {

	private final ObjectMapper objectMapper;

	public SimulationRunReportWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper.copy().findAndRegisterModules();
	}

	public WrittenReports write(Path directory, SimulationRunLedger.Snapshot snapshot) {
		if (directory == null) throw new IllegalArgumentException("report directory is required");
		if (snapshot == null) throw new IllegalArgumentException("report snapshot is required");
		try {
			Path normalizedDirectory = directory.toAbsolutePath().normalize();
			Files.createDirectories(normalizedDirectory);
			String safeRunId = safeFilePart(snapshot.runId());
			Path json = normalizedDirectory.resolve(safeRunId + ".json");
			Path markdown = normalizedDirectory.resolve(safeRunId + ".md");
			atomicWrite(json, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(snapshot));
			atomicWrite(markdown, renderMarkdown(snapshot).getBytes(StandardCharsets.UTF_8));
			return new WrittenReports(json, markdown);
		} catch (IOException exception) {
			throw new IllegalStateException("Simulation report could not be written", exception);
		}
	}

	private static String renderMarkdown(SimulationRunLedger.Snapshot snapshot) {
		StringBuilder result = new StringBuilder()
				.append("# SoundConnect simulation run\n\n")
				.append("- Run: `").append(snapshot.runId()).append("`\n")
				.append("- World: `").append(snapshot.worldId()).append("`\n")
				.append("- Seed: `").append(snapshot.seed()).append("`\n")
				.append("- Started: `").append(snapshot.startedAt()).append("`\n")
				.append("- Completed: `").append(snapshot.completedAt()).append("`\n\n")
				.append("## Totals\n\n")
				.append("| Status | Count |\n| --- | ---: |\n");
		for (SimulationRunLedger.Status status : SimulationRunLedger.Status.values()) {
			result.append("| ").append(status).append(" | ")
					.append(snapshot.totals().getOrDefault(status, 0L)).append(" |\n");
		}
		result.append("\n## Planned baseline\n\n| Metric | Count |\n| --- | ---: |\n");
		snapshot.planned().forEach((metric, count) -> result.append("| ")
				.append(escapeCell(metric)).append(" | ").append(count).append(" |\n"));
		result.append("\n## Timeline\n\n| # | Phase | Action | Actor | Target | Status | Detail |\n")
				.append("| ---: | --- | --- | --- | --- | --- | --- |\n");
		for (SimulationRunLedger.Entry entry : snapshot.entries()) {
			result.append("| ").append(entry.sequence()).append(" | ")
					.append(escapeCell(entry.phase())).append(" | ")
					.append(escapeCell(entry.action())).append(" | ")
					.append(escapeCell(entry.actorKey())).append(" | ")
					.append(escapeCell(entry.targetKey())).append(" | ")
					.append(entry.status()).append(" | ")
					.append(escapeCell(entry.detail())).append(" |\n");
		}
		return result.toString();
	}

	private static void atomicWrite(Path target, byte[] content) throws IOException {
		Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
		try {
			Files.write(temporary, content);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static String safeFilePart(String value) {
		String normalized = value == null ? "simulation" : value.toLowerCase(Locale.ROOT);
		normalized = normalized.replaceAll("[^a-z0-9._-]", "-");
		if (normalized.isBlank()) return "simulation";
		return normalized.length() <= 120 ? normalized : normalized.substring(0, 120);
	}

	private static String escapeCell(String value) {
		if (value == null) return "";
		return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
	}

	public record WrittenReports(Path json, Path markdown) {
	}
}
