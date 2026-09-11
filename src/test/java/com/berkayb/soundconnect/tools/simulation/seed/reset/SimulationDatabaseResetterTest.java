package com.berkayb.soundconnect.tools.simulation.seed.reset;

import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SimulationDatabaseResetterTest {

	@Mock
	private SimulationRuntimeGuard runtimeGuard;
	@Mock
	private SimulationFreshResetAuthorization resetAuthorization;
	@Mock
	private JdbcTemplate jdbcTemplate;

	@Test
	void executesOneFixedQuotedStatementAfterBothSafetyChecks() {
		SimulationDatabaseResetter resetter = resetter();

		resetter.resetApplicationData();

		InOrder order = inOrder(runtimeGuard, resetAuthorization, jdbcTemplate);
		order.verify(runtimeGuard).assertRuntimeAllowed();
		order.verify(resetAuthorization).assertFreshResetAuthorized();
		order.verify(jdbcTemplate).execute(
				"TRUNCATE TABLE \"tbl_user\", \"tbl_band\", \"tbl_collab_actor\", "
						+ "\"tbl_collab_notification_outbox\", \"tbl_dm_conversation\", \"tbl_dm_message\", "
						+ "\"tbl_event_performer_notification_outbox\", \"tbl_media_asset\", \"tbl_notification\", "
						+ "\"tbl_notification_receipt\", \"tbl_overthinking_notification_outbox\", "
						+ "\"tbl_profile_media\", \"tbl_table_group\", \"tbl_table_group_notification_outbox\", "
						+ "\"tbl_tracks\", \"tbl_venue_analytics_presence\", \"tbl_venue_analytics_receipt\", "
						+ "\"tbl_venue_analytics_recent_detail\", \"tbl_venue_analytics_state\", "
						+ "\"tbl_venue_suggestion\" RESTART IDENTITY CASCADE");
		order.verifyNoMoreInteractions();
	}

	@Test
	void neverTouchesDatabaseWhenRuntimeGuardRejectsInvocation() {
		doThrow(new IllegalStateException("unsafe runtime"))
				.when(runtimeGuard).assertRuntimeAllowed();

		assertThatThrownBy(() -> resetter().resetApplicationData())
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("unsafe runtime");

		verify(resetAuthorization, never()).assertFreshResetAuthorized();
		verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void neverTouchesDatabaseWhenFreshResetIsNotAuthorized() {
		doThrow(new IllegalStateException("ack required"))
				.when(resetAuthorization).assertFreshResetAuthorized();

		assertThatThrownBy(() -> resetter().resetApplicationData())
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("ack required");

		verify(runtimeGuard).assertRuntimeAllowed();
		verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());
	}

	private SimulationDatabaseResetter resetter() {
		return new SimulationDatabaseResetter(runtimeGuard, resetAuthorization, jdbcTemplate);
	}
}
