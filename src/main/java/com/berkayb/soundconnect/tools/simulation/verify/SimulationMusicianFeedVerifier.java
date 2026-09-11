package com.berkayb.soundconnect.tools.simulation.verify;

import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemResponse;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedItemType;
import com.berkayb.soundconnect.modules.feed.musician.api.MusicianFeedPageResponse;
import com.berkayb.soundconnect.modules.feed.musician.core.MusicianFeedService;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/** Deterministic post-seed smoke check from all three musician observer perspectives. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public class SimulationMusicianFeedVerifier {

	private static final int PAGE_SIZE = 20;
	private static final int MAX_PAGES = 3;
	private static final int ORGANIC_ITEMS_PER_PROMOTION = 8;
	private static final Set<MusicianFeedItemType> MODULE_SHARES = Set.of(
			MusicianFeedItemType.OVERTHINKING_PROFILE_SHARE,
			MusicianFeedItemType.TABLEGROUP_PROFILE_SHARE);
	private static final List<String> SUPPORTED_TYPES = Arrays.stream(MusicianFeedItemType.values())
			.map(Enum::name).toList();

	private final SimulationRuntimeGuard runtimeGuard;
	private final MusicianFeedService feedService;
	private final LongSupplier nanoTime;

	@Autowired
	public SimulationMusicianFeedVerifier(
			SimulationRuntimeGuard runtimeGuard,
			MusicianFeedService feedService
	) {
		this(runtimeGuard, feedService, System::nanoTime);
	}

	SimulationMusicianFeedVerifier(
			SimulationRuntimeGuard runtimeGuard,
			MusicianFeedService feedService,
			LongSupplier nanoTime
	) {
		this.runtimeGuard = Objects.requireNonNull(runtimeGuard, "runtimeGuard");
		this.feedService = Objects.requireNonNull(feedService, "feedService");
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
	}

	public SimulationFeedVerificationResult verify(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			SimulationRunLedger ledger
	) {
		runtimeGuard.assertRuntimeAllowed();
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(accountUserIds, "accountUserIds");
		Objects.requireNonNull(ledger, "ledger");
		Set<UUID> forbiddenAuthors = manifest.accounts().stream()
				.filter(account -> account.listenerVisibility()
						== SimulationWorldManifest.ListenerVisibilityState.GHOST)
				.map(account -> accountUserIds.get(account.key()))
				.filter(Objects::nonNull)
				.collect(Collectors.toUnmodifiableSet());
		Map<String, SimulationFeedVerificationResult.ObserverResult> results = new LinkedHashMap<>();

		for (SimulationWorldManifest.Account observer : manifest.accounts().stream()
				.filter(account -> account.observer() != SimulationWorldManifest.ObserverProfile.NONE)
				.sorted(java.util.Comparator.comparing(account -> account.observer().ordinal()))
				.toList()) {
			UUID userId = requireId(accountUserIds, observer.key());
			try {
				SimulationFeedVerificationResult.ObserverResult result = verifyObserver(
						observer, userId, forbiddenAuthors);
				results.put(observer.key(), result);
				ledger.succeeded("feed-verification", "READ_OBSERVER_FEED", observer.key(), null,
						result.items() + " items / " + result.elapsedMillis() + " ms");
			} catch (RuntimeException failure) {
				ledger.failed("feed-verification", "READ_OBSERVER_FEED", observer.key(), null, failure);
				throw failure;
			}
		}
		if (results.size() != 3) {
			throw new IllegalStateException("Simulation feed verification requires exactly three observers");
		}
		return new SimulationFeedVerificationResult(results);
	}

	private SimulationFeedVerificationResult.ObserverResult verifyObserver(
			SimulationWorldManifest.Account observer,
			UUID userId,
			Set<UUID> forbiddenAuthors
	) {
		long started = nanoTime.getAsLong();
		String cursor = null;
		UUID sessionId = null;
		List<MusicianFeedItemResponse> all = new ArrayList<>();
		Set<String> ids = new HashSet<>();
		long previousPosition = -1L;
		int pages = 0;
		for (; pages < MAX_PAGES; pages++) {
			MusicianFeedPageResponse page = feedService.get(userId, PAGE_SIZE, cursor, SUPPORTED_TYPES);
			if (page == null) throw new IllegalStateException("Musician feed returned no page");
			if (sessionId == null) sessionId = page.feedSessionId();
			else if (!Objects.equals(sessionId, page.feedSessionId())) {
				throw new IllegalStateException("Musician feed cursor changed session identity");
			}
			for (MusicianFeedItemResponse item : page.items()) {
				if (item == null || item.id() == null || !ids.add(item.id())) {
					throw new IllegalStateException("Musician feed emitted a duplicate or null item");
				}
				if (item.position() < 0 || item.position() <= previousPosition) {
					throw new IllegalStateException("Musician feed positions are not strictly increasing");
				}
				previousPosition = item.position();
				assertVisibleAuthor(item.author(), forbiddenAuthors);
				if (item.reason() != null) {
					item.reason().actors().forEach(actor -> assertVisibleAuthor(actor, forbiddenAuthors));
				}
				all.add(item);
			}
			cursor = page.nextCursor();
			if (!page.hasMore() || cursor == null || cursor.isBlank()) {
				pages++;
				break;
			}
		}
		assertContentSpacing(all);
		if (observer.observer() == SimulationWorldManifest.ObserverProfile.INCOMPLETE
				&& all.stream().noneMatch(item -> item.type() == MusicianFeedItemType.PROFILE_COMPLETION)) {
			throw new IllegalStateException("Incomplete observer feed omitted profile-completion guidance");
		}
		Map<MusicianFeedItemType, Long> composition = all.stream().collect(Collectors.groupingBy(
				MusicianFeedItemResponse::type,
				() -> new EnumMap<>(MusicianFeedItemType.class),
				Collectors.counting()));
		long elapsedMillis = Math.max(0L, (nanoTime.getAsLong() - started) / 1_000_000L);
		return new SimulationFeedVerificationResult.ObserverResult(
				pages, all.size(), elapsedMillis, composition, all.stream().map(MusicianFeedItemResponse::id).toList());
	}

	private static void assertVisibleAuthor(
			MusicianFeedItemResponse.Author author,
			Set<UUID> forbiddenAuthors
	) {
		if (author == null) return;
		if (forbiddenAuthors.contains(author.userId())) {
			throw new IllegalStateException("Ghost listener leaked into musician feed authorship");
		}
		if ("ORGANIZER".equals(author.profileType()) || "PRODUCER".equals(author.profileType())) {
			throw new IllegalStateException("Unsupported profile type leaked into musician feed");
		}
	}

	private static void assertContentSpacing(List<MusicianFeedItemResponse> items) {
		int organicSincePromotion = 0;
		MusicianFeedItemType previous = null;
		for (MusicianFeedItemResponse item : items) {
			if (item.type() == MusicianFeedItemType.SPONSORED) {
				if (organicSincePromotion < ORGANIC_ITEMS_PER_PROMOTION) {
					throw new IllegalStateException("Sponsored item violated organic spacing");
				}
				organicSincePromotion = 0;
			} else {
				organicSincePromotion++;
			}
			if (MODULE_SHARES.contains(item.type()) && MODULE_SHARES.contains(previous)) {
				throw new IllegalStateException("Mainstage profile shares appeared consecutively");
			}
			previous = item.type();
		}
	}

	private static UUID requireId(Map<String, UUID> ids, String key) {
		UUID id = ids.get(key);
		if (id == null) throw new IllegalStateException("Observer account was not materialized: " + key);
		return id;
	}
}
