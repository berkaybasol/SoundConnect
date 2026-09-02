package com.berkayb.soundconnect.modules.tablegroup.game.support;

import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TableGroupGameTimeProvider {
	public Instant now() {
		return Instant.now();
	}
}
