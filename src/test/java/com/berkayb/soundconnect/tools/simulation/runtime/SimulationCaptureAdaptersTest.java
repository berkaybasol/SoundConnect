package com.berkayb.soundconnect.tools.simulation.runtime;

import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.SimulationMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SimulationCaptureAdaptersTest {

	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	@AfterEach
	void shutdownExecutor() {
		executor.shutdownNow();
	}

	@Test
	void otpAwaitReceivesAndAtomicallyConsumesCapturedCode() throws Exception {
		SimulationProperties properties = properties(2, 10);
		SimulationOtpCaptureMailService service = otpService(properties);
		Future<Optional<String>> waiting = executor.submit(() -> service.awaitAndConsume(
				"artist@simulation.soundconnect.invalid", Duration.ofSeconds(2)));

		service.sendVerificationMail("ARTIST@SIMULATION.SOUNDCONNECT.INVALID", "123456");

		assertThat(waiting.get()).contains("123456");
		assertThat(service.consume("artist@simulation.soundconnect.invalid")).isEmpty();
		assertThat(service.pendingCount()).isZero();
	}

	@Test
	void otpCaptureIsBoundedButAllowsReplacementForSameRecipient() {
		SimulationProperties properties = properties(1, 10);
		SimulationOtpCaptureMailService service = otpService(properties);
		service.sendVerificationMail("one@simulation.soundconnect.invalid", "111111");
		service.sendVerificationMail("one@simulation.soundconnect.invalid", "222222");

		assertThat(service.consume("one@simulation.soundconnect.invalid")).contains("222222");
		service.sendVerificationMail("one@simulation.soundconnect.invalid", "333333");
		assertThatThrownBy(() -> service.sendVerificationMail(
				"two@simulation.soundconnect.invalid", "444444"))
				.hasMessageContaining("capacity exceeded");
	}

	@Test
	void otpCaptureRejectsExternalRecipientAndMalformedCode() {
		SimulationOtpCaptureMailService service = otpService(properties(2, 10));

		assertThatThrownBy(() -> service.sendVerificationMail("person@example.com", "123456"))
				.hasMessageContaining("outside the configured .invalid suffix");
		assertThatThrownBy(() -> service.sendVerificationMail(
				"person@simulation.soundconnect.invalid", "12345"))
				.hasMessageContaining("exactly six digits");
	}

	@Test
	void outboundMailNeverSendsAndKeepsOnlyBoundedAllowedHistory() {
		SimulationProperties properties = properties(2, 2);
		SimulationMailCaptureClient client = new SimulationMailCaptureClient(
				mock(SimulationRuntimeGuard.class),
				new SimulationRecipientPolicy(properties),
				properties,
				Clock.fixed(Instant.parse("2026-09-11T10:15:30Z"), ZoneOffset.UTC)
		);

		client.send("one@simulation.soundconnect.invalid", "one", "body", null);
		client.send("two@simulation.soundconnect.invalid", "two", null, "<p>body</p>");
		client.send("three@simulation.soundconnect.invalid", "three", "body", null);

		assertThat(client.snapshot())
				.extracting(SimulationMailCaptureClient.CapturedMail::recipient)
				.containsExactly(
						"two@simulation.soundconnect.invalid",
						"three@simulation.soundconnect.invalid");
		assertThat(client.snapshot()).allMatch(mail -> mail.capturedAt()
				.equals(Instant.parse("2026-09-11T10:15:30Z")));
		assertThat(client.droppedCount()).isEqualTo(1L);
	}

	@Test
	void outboundCaptureFailsClosedForAnyDeliverableRecipient() {
		SimulationProperties properties = properties(2, 2);
		SimulationMailCaptureClient client = new SimulationMailCaptureClient(
				mock(SimulationRuntimeGuard.class),
				new SimulationRecipientPolicy(properties),
				properties,
				Clock.systemUTC()
		);

		assertThatThrownBy(() -> client.send("real@example.com", "subject", "body", null))
				.hasMessageContaining("outside the configured .invalid suffix");
		assertThat(client.snapshot()).isEmpty();
	}

	private static SimulationOtpCaptureMailService otpService(SimulationProperties properties) {
		return new SimulationOtpCaptureMailService(
				mock(SimulationRuntimeGuard.class),
				new SimulationRecipientPolicy(properties),
				properties
		);
	}

	private static SimulationProperties properties(int maxAccounts, int mailCapacity) {
		SimulationProperties properties = new SimulationProperties();
		properties.setEnabled(true);
		properties.setMode(SimulationMode.FRESH);
		properties.setMaxAccounts(maxAccounts);
		properties.setEmailSuffix("@simulation.soundconnect.invalid");
		properties.setExpectedPostgresqlDatabase("soundconnect");
		properties.setDestructiveResetAcknowledged(true);
		properties.setCommonPassword("local-only-password");
		properties.setReportDirectory(Path.of("build", "reports", "simulation"));
		properties.setMailCaptureCapacity(mailCapacity);
		return properties;
	}
}
