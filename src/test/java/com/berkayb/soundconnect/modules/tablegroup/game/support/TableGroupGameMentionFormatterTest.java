package com.berkayb.soundconnect.modules.tablegroup.game.support;

import com.berkayb.soundconnect.modules.tablegroup.game.enums.TableGroupGameOutcome;
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

	@Test
	void resultMessageUsesTheSanitizedContextualUsername() {
		assertThat(TableGroupGameMentionFormatter.resultMessage(
				TableGroupGameOutcome.VOLUNTEER,
				" @@ghost\nlistener "
		)).contains("@ghost listener").doesNotContain("\n");
	}
}
