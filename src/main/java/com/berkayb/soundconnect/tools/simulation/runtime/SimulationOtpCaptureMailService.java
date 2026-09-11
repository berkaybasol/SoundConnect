package com.berkayb.soundconnect.tools.simulation.runtime;

import com.berkayb.soundconnect.auth.otp.service.OtpMailService;
import com.berkayb.soundconnect.tools.simulation.SimulationProperties;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local-only OTP mailbox. Codes remain in memory, are never logged or sent, and
 * are removed atomically by the first consumer.
 */
public final class SimulationOtpCaptureMailService implements OtpMailService {

	private static final Duration MAX_AWAIT = Duration.ofMinutes(5);

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationRecipientPolicy recipientPolicy;
	private final int capacity;
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition codeArrived = lock.newCondition();
	private final Map<String, String> pendingCodes = new HashMap<>();

	public SimulationOtpCaptureMailService(
			SimulationRuntimeGuard runtimeGuard,
			SimulationRecipientPolicy recipientPolicy,
			SimulationProperties properties
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.recipientPolicy = Objects.requireNonNull(recipientPolicy, "recipientPolicy");
		this.capacity = properties.getMaxAccounts();
		if (capacity < 1 || capacity > 50) {
			throw new IllegalArgumentException("Simulation OTP capacity must be between 1 and 50");
		}
	}

	@Override
	public void sendVerificationMail(String to, String code) {
		runtimeGuard.assertRuntimeAllowed();
		String recipient = recipientPolicy.requireAllowed(to);
		if (code == null || !code.matches("\\d{6}")) {
			throw new IllegalArgumentException("Simulation OTP must contain exactly six digits");
		}

		lock.lock();
		try {
			if (!pendingCodes.containsKey(recipient) && pendingCodes.size() >= capacity) {
				throw new IllegalStateException("Simulation OTP capture capacity exceeded");
			}
			pendingCodes.put(recipient, code);
			codeArrived.signalAll();
		} finally {
			lock.unlock();
		}
	}

	/**
	 * Waits for one code and consumes it atomically. A timeout of zero performs an
	 * immediate, non-blocking consume.
	 */
	public Optional<String> awaitAndConsume(String email, Duration timeout) throws InterruptedException {
		runtimeGuard.assertRuntimeAllowed();
		String recipient = recipientPolicy.requireAllowed(email);
		if (timeout == null || timeout.isNegative() || timeout.compareTo(MAX_AWAIT) > 0) {
			throw new IllegalArgumentException("Simulation OTP wait must be between zero and five minutes");
		}

		long remaining = timeout.toNanos();
		lock.lockInterruptibly();
		try {
			while (!pendingCodes.containsKey(recipient)) {
				if (remaining <= 0L) return Optional.empty();
				remaining = codeArrived.awaitNanos(remaining);
			}
			return Optional.of(pendingCodes.remove(recipient));
		} finally {
			lock.unlock();
		}
	}

	public Optional<String> consume(String email) {
		try {
			return awaitAndConsume(email, Duration.ZERO);
		} catch (InterruptedException impossible) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Unexpected interruption during non-blocking OTP consume", impossible);
		}
	}

	public int pendingCount() {
		runtimeGuard.assertRuntimeAllowed();
		lock.lock();
		try {
			return pendingCodes.size();
		} finally {
			lock.unlock();
		}
	}
}
