package com.berkayb.soundconnect.tools.simulation.verify;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPageResponse;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedService;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SimulationMusicianFeedVerifierTest {

	private final SimulationWorldManifest manifest = new SimulationWorldManifestLoader(new ObjectMapper())
			.loadDefault();
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T12:00:00Z"), ZoneOffset.UTC);

	@Test
	void springSelectsTheProductionConstructorWhenTheTestSeamIsAlsoPresent() {
		SimulationRuntimeGuard runtimeGuard = mock(SimulationRuntimeGuard.class);
		MusicianFeedService feedService = mock(MusicianFeedService.class);
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles("local", "simulation");
			TestPropertyValues.of("app.simulation.enabled=true").applyTo(context);
			context.registerBean(SimulationRuntimeGuard.class, () -> runtimeGuard);
			context.registerBean(MusicianFeedService.class, () -> feedService);
			context.register(SimulationMusicianFeedVerifier.class);
			context.refresh();

			assertThat(context.getBean(SimulationMusicianFeedVerifier.class)).isNotNull();
		}
	}

	@Test
	void readsAllObserversAndAcceptsTheEightOrganicSponsorBoundary() {
		MusicianFeedService feed = mock(MusicianFeedService.class);
		when(feed.get(any(), anyInt(), isNull(), anyList())).thenAnswer(invocation -> {
			UUID viewer = invocation.getArgument(0);
			List<MusicianFeedItemResponse> items = new ArrayList<>();
			for (int index = 0; index < 8; index++) {
				items.add(item(viewer + "-organic-" + index, MusicianFeedItemType.TRACK, index));
			}
			items.add(item(viewer + "-sponsor", MusicianFeedItemType.SPONSORED, 8));
			items.add(item(viewer + "-completion", MusicianFeedItemType.PROFILE_COMPLETION, 9));
			return new MusicianFeedPageResponse(1, "test", UUID.randomUUID(), clock.instant(), items, null, false);
		});
		AtomicLong nanos = new AtomicLong();
		SimulationMusicianFeedVerifier verifier = new SimulationMusicianFeedVerifier(
				mock(SimulationRuntimeGuard.class), feed, () -> nanos.getAndAdd(5_000_000L));

		SimulationFeedVerificationResult result = verifier.verify(
				manifest, ids(), new SimulationRunLedger(manifest.worldId(), manifest.seed(), clock));

		assertThat(result.observers()).hasSize(3);
		assertThat(result.observers().values()).allMatch(observer -> observer.items() == 10);
	}

	@Test
	void failsWhenSponsorArrivesBeforeItsOrganicBoundary() {
		MusicianFeedService feed = mock(MusicianFeedService.class);
		when(feed.get(any(), anyInt(), isNull(), anyList())).thenReturn(new MusicianFeedPageResponse(
				1, "test", UUID.randomUUID(), clock.instant(),
				List.of(item("too-early", MusicianFeedItemType.SPONSORED, 0)), null, false));
		SimulationMusicianFeedVerifier verifier = new SimulationMusicianFeedVerifier(
				mock(SimulationRuntimeGuard.class), feed, System::nanoTime);

		assertThatThrownBy(() -> verifier.verify(
				manifest, ids(), new SimulationRunLedger(manifest.worldId(), manifest.seed(), clock)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("organic spacing");
	}

	private Map<String, UUID> ids() {
		Map<String, UUID> ids = new LinkedHashMap<>();
		manifest.accounts().forEach(account -> ids.put(account.key(), UUID.randomUUID()));
		return ids;
	}

	private MusicianFeedItemResponse item(String id, MusicianFeedItemType type, long position) {
		return new MusicianFeedItemResponse(
				id, type, 1, clock.instant(), null, null, null, null, null, List.of(), null)
				.withDelivery(position, "impression-" + position);
	}
}
