package com.berkayb.soundconnect.modules.tablegroup.game.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TableGroupGameMentionFormatterTest {

	@Test
	void collapsesLeadingAtSignsAndPreventsLineBreakInjection() {
		assertThat(TableGroupGameMentionFormatter.mention("  @@@ece\r\nteam  "))
				.isEqualTo("@ece  team");
	}

	@Test
	void leavesOrdinaryCanonicalUsernameCopyUnchanged() {
		assertThat(TableGroupGameMentionFormatter.mention("ece")).isEqualTo("@ece");
	}
}
