package com.berkayb.soundconnect.tools.simulation.runtime;

import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Terminal no-send adapter for simulation mail. It retains a bounded diagnostic
 * history for allowed reserved recipients and has no network-capable dependency.
 */
public final class SimulationMailCaptureClient implements MailSenderClient {

	private static final int MAX_SUBJECT_CHARS = 512;
	private static final int MAX_BODY_CHARS = 16_384;

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationRecipientPolicy recipientPolicy;
	private final int capacity;
	private final Clock clock;
	private final Object monitor = new Object();
	private final Deque<CapturedMail> captured = new ArrayDeque<>();
	private long droppedCount;

	public SimulationMailCaptureClient(
			SimulationRuntimeGuard runtimeGuard,
			SimulationRecipientPolicy recipientPolicy,
			SimulationProperties properties,
			Clock clock
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.recipientPolicy = Objects.requireNonNull(recipientPolicy, "recipientPolicy");
		this.capacity = properties.getMailCaptureCapacity();
		if (capacity < 1 || capacity > 5_000) {
			throw new IllegalArgumentException("Simulation mail capture capacity must be between 1 and 5000");
		}
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public void send(String to, String subject, String textBody, String htmlBody) {
		runtimeGuard.assertRuntimeAllowed();
		String recipient = recipientPolicy.requireAllowed(to);
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("Simulation mail subject is required");
		}
		if ((textBody == null || textBody.isBlank()) && (htmlBody == null || htmlBody.isBlank())) {
			throw new IllegalArgumentException("Simulation mail requires a text or HTML body");
		}

		CapturedMail message = new CapturedMail(
				recipient,
				bounded(subject.trim(), MAX_SUBJECT_CHARS),
				bounded(textBody, MAX_BODY_CHARS),
				bounded(htmlBody, MAX_BODY_CHARS),
				clock.instant()
		);
		synchronized (monitor) {
			if (captured.size() == capacity) {
				captured.removeFirst();
				droppedCount++;
			}
			captured.addLast(message);
		}
	}

	public List<CapturedMail> snapshot() {
		runtimeGuard.assertRuntimeAllowed();
		synchronized (monitor) {
			return List.copyOf(new ArrayList<>(captured));
		}
	}

	public long droppedCount() {
		runtimeGuard.assertRuntimeAllowed();
		synchronized (monitor) {
			return droppedCount;
		}
	}

	private static String bounded(String value, int maxLength) {
		if (value == null) return null;
		return value.length() <= maxLength ? value : value.substring(0, maxLength);
	}

	public record CapturedMail(
			String recipient,
			String subject,
			String textBody,
			String htmlBody,
			Instant capturedAt
	) {
	}
}
