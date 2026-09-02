package com.berkayb.soundconnect.modules.tablegroup.game.random;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class TableGroupDiceRoller {
	private final SecureRandom random = new SecureRandom();

	public int roll() {
		return random.nextInt(6) + 1;
	}
}
