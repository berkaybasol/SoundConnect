package com.berkayb.soundconnect.modules.profile.ListenerProfile.support;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

@Component
public class ListenerVisibilityTimeProvider {

	private final Clock clock;

	public ListenerVisibilityTimeProvider() {
		this(Clock.systemUTC());
	}

	ListenerVisibilityTimeProvider(Clock clock) {
		this.clock = clock;
	}

	public LocalDateTime now() {
		return LocalDateTime.now(clock);
	}
}
