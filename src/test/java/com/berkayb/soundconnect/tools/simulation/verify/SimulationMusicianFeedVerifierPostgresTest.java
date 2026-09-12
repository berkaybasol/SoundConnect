package com.berkayb.soundconnect.tools.simulation.verify;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedFeedbackAction;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPageResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedReasonCode;
import com.berkayb.soundconnect.modules.feed.musician.candidate.MusicianFeedLane;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedProperties;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedService;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryService;
import com.berkayb.soundconnect.modules.feed.musician.delivery.MusicianFeedDeliveryTokenCodec;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Uses real delivery/replay persistence to verify the smoke test cannot consume browsing state. */
@Testcontainers(disabledWithoutDocker = true)
class SimulationMusicianFeedVerifierPostgresTest {

	@Container
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4-alpine")
			.withDatabaseName("simulation_feed_verifier")
			.withUsername("soundconnect").withPassword("soundconnect");

	private final SimulationWorldManifest manifest = new SimulationWorldManifestLoader(new ObjectMapper())
			.loadDefault();
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-12T12:00:00Z"), ZoneOffset.UTC);
	private final Set<MusicianFeedItemType> supportedTypes = Set.copyOf(Arrays.asList(MusicianFeedItemType.values()));
	private final Map<String, UUID> accountIds = new LinkedHashMap<>();
	private final Map<UUID, UUID> verificationSessions = new LinkedHashMap<>();
	private DataSourceTransactionManager transactionManager;
	private NamedParameterJdbcTemplate jdbc;
	private MusicianFeedDeliveryService deliveries;
	private MusicianFeedService feed;
	private UUID existingViewer;
	private MusicianFeedItemResponse existingDelivery;

	@BeforeEach
	void setUp() throws Exception {
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new NamedParameterJdbcTemplate(dataSource);
		jdbc.getJdbcTemplate().execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; "
				+ "CREATE TABLE tbl_user(id uuid primary key)");
		jdbc.getJdbcTemplate().execute(Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-delivery.sql")));
		jdbc.getJdbcTemplate().execute(Files.readString(Path.of("scripts/db/2026-09-11-musician-feed-replay.sql")));
		transactionManager = new DataSourceTransactionManager(dataSource);
		manifest.accounts().forEach(account -> {
			UUID userId = UUID.randomUUID();
			accountIds.put(account.key(), userId);
			jdbc.update("insert into tbl_user(id) values(:id)", Map.of("id", userId));
		});
		existingViewer = manifest.accounts().stream()
				.filter(account -> account.observer() != SimulationWorldManifest.ObserverProfile.NONE)
				.map(account -> accountIds.get(account.key())).findFirst().orElseThrow();
		MusicianFeedProperties properties = new MusicianFeedProperties();
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		deliveries = new MusicianFeedDeliveryService(jdbc,
				new MusicianFeedDeliveryTokenCodec(mapper, properties), properties, mapper);
		existingDelivery = new TransactionTemplate(transactionManager).execute(status -> deliveries.recordPage(
				existingViewer, UUID.randomUUID(), clock.instant(), 1, "test", 0,
				List.of(item("human-delivery", MusicianFeedItemType.TRACK, null)), clock.instant()).getFirst());
		feed = mock(MusicianFeedService.class);
	}

	@Test
	void preservesContinuationInsideEachObserverTransactionThenRollsBackDeliveryAndReplay() {
		stubPersistedFeed(false);
		SimulationMusicianFeedVerifier verifier = verifier();
		UUID callerWrite = UUID.randomUUID();

		new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
			jdbc.update("insert into tbl_user(id) values(:id)", Map.of("id", callerWrite));
			SimulationFeedVerificationResult result = verifier.verify(manifest, accountIds, ledger());

			assertThat(result.observers()).hasSize(3);
			assertThat(result.observers().values()).allSatisfy(observer -> {
				assertThat(observer.pages()).isEqualTo(2);
				assertThat(observer.items()).isEqualTo(10);
			});
			assertThat(outer.isRollbackOnly()).isFalse();
			assertThat(jdbc.queryForObject("select count(*) from tbl_user where id=:id",
					Map.of("id", callerWrite), Long.class)).isEqualTo(1L);
		});

		assertThat(verificationSessions).hasSize(3);
		assertOnlyHumanHistoryRemains();
		assertThat(jdbc.queryForObject("select count(*) from tbl_user where id=:id",
				Map.of("id", callerWrite), Long.class)).isEqualTo(1L);
	}

	@Test
	void failedVerificationAlsoRollsBackEveryPageWhilePreservingExistingHistory() {
		stubPersistedFeed(true);

		assertThatThrownBy(() -> verifier().verify(manifest, accountIds, ledger()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("organic spacing");

		assertOnlyHumanHistoryRemains();
	}

	private void stubPersistedFeed(boolean invalidSecondPromotion) {
		when(feed.get(any(), anyInt(), nullable(String.class), anyList())).thenAnswer(invocation -> {
			UUID viewer = invocation.getArgument(0);
			String cursor = invocation.getArgument(2);
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
			assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
			// A separate connection must never see any automated delivery, even mid-verification.
			assertThat(committedDeliveryCount()).isEqualTo(1L);
			if (cursor == null) {
				UUID session = UUID.randomUUID();
				verificationSessions.put(viewer, session);
				assertThat(deliveries.snapshot(viewer, session, clock.instant()).nextAbsolutePosition()).isZero();
				List<MusicianFeedItemResponse> items = new ArrayList<>();
				for (int index = 0; index < 8; index++) {
					items.add(item("organic-" + index, MusicianFeedItemType.TRACK, null));
				}
				items.add(item("featured-collab", MusicianFeedItemType.COLLAB, UUID.randomUUID()));
				List<MusicianFeedItemResponse> recorded = deliveries.recordPage(viewer, session,
						clock.instant(), 1, "test", 0, items, clock.instant());
				return new MusicianFeedPageResponse(1, "test", session, clock.instant(), recorded, "next", true);
			}
			UUID session = verificationSessions.get(viewer);
			var snapshot = deliveries.snapshot(viewer, session, clock.instant());
			assertThat(snapshot.nextAbsolutePosition()).isEqualTo(9);
			assertThat(snapshot.campaignIds()).hasSize(1);
			assertThat(snapshot.deliveredPromotionCount()).isEqualTo(1);
			assertThat(snapshot.organicCountAtLastPromotion()).isEqualTo(8);
			List<MusicianFeedItemResponse> continuation = new ArrayList<>();
			if (invalidSecondPromotion) {
				continuation.add(item("too-early-event", MusicianFeedItemType.EVENT, UUID.randomUUID()));
			}
			continuation.add(item("completion", MusicianFeedItemType.PROFILE_COMPLETION, null));
			MusicianFeedPageResponse page = deliveries.recordPageAndReplay(viewer, session,
					clock.instant(), 1, "test", 9, "x".repeat(43), 20, supportedTypes, continuation,
					continuation.stream().map(item -> MusicianFeedLane.GENERAL_DISCOVERY).toList(),
					null, false, clock.instant());
			assertThat(jdbc.queryForObject("select count(*) from tbl_musician_feed_page_replay",
					Map.of(), Long.class)).isEqualTo(1L);
			return page;
		});
	}

	private void assertOnlyHumanHistoryRemains() {
		assertThat(committedDeliveryCount()).isEqualTo(1L);
		assertThat(jdbc.queryForObject("select count(*) from tbl_musician_feed_page_replay",
				Map.of(), Long.class)).isZero();
		assertThat(deliveries.require(existingDelivery.impressionToken(), existingViewer,
				"human-delivery", clock.instant()).itemId()).isEqualTo("human-delivery");
		verificationSessions.forEach((viewer, session) -> {
			var snapshot = deliveries.snapshot(viewer, session, clock.instant());
			assertThat(snapshot.nextAbsolutePosition()).isZero();
			assertThat(snapshot.deliveredPromotionCount()).isZero();
			assertThat(snapshot.campaignIds()).isEmpty();
		});
	}

	private long committedDeliveryCount() {
		try (var connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
			 var statement = connection.createStatement();
			 var rows = statement.executeQuery("select count(*) from tbl_musician_feed_delivery")) {
			rows.next();
			return rows.getLong(1);
		} catch (java.sql.SQLException failure) {
			throw new AssertionError(failure);
		}
	}

	private MusicianFeedItemResponse item(String id, MusicianFeedItemType type, UUID campaignId) {
		var author = new MusicianFeedItemResponse.Author(UUID.randomUUID(), UUID.randomUUID(),
				"MUSICIAN", "artist", "artist", null, true);
		var promotion = campaignId == null ? null
				: new MusicianFeedItemResponse.Promotion(campaignId, "Sponsorlu", "Aç", "/collab");
		return new MusicianFeedItemResponse(id, type, 1, clock.instant(),
				new MusicianFeedItemResponse.Reason(promotion == null
						? MusicianFeedReasonCode.FOLLOWING_PUBLICATION : MusicianFeedReasonCode.SPONSORED,
						List.of(author), 0), author,
				new MusicianFeedItemResponse.Target(type == MusicianFeedItemType.COLLAB ? "COLLAB" : "MEDIA",
						UUID.randomUUID()), null, promotion, List.of(MusicianFeedFeedbackAction.HIDE), Map.of());
	}

	private SimulationMusicianFeedVerifier verifier() {
		return new SimulationMusicianFeedVerifier(mock(SimulationRuntimeGuard.class), feed, transactionManager);
	}

	private SimulationRunLedger ledger() {
		return new SimulationRunLedger(manifest.worldId(), manifest.seed(), clock);
	}
}
