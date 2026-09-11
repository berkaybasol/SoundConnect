package com.berkayb.soundconnect.tools.simulation;

import com.berkayb.soundconnect.tools.simulation.behavior.SimulationBehaviorProperties;
import com.berkayb.soundconnect.tools.simulation.behavior.SimulationPopulationEngine;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunReportWriter;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationRuntimeStatus;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationWorldLease;
import com.berkayb.soundconnect.tools.simulation.seed.account.SimulationAccountRegistrationOrchestrator;
import com.berkayb.soundconnect.tools.simulation.seed.account.SimulationControlAdmin;
import com.berkayb.soundconnect.tools.simulation.seed.account.SimulationControlAdminProvisioner;
import com.berkayb.soundconnect.tools.simulation.seed.account.SimulationRegisteredAccount;
import com.berkayb.soundconnect.tools.simulation.seed.band.SimulationBandMaterializationResult;
import com.berkayb.soundconnect.tools.simulation.seed.band.SimulationBandMaterializer;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlanner;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunitySeedResult;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunitySeeder;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityWindow;
import com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationSocialContentSeedResult;
import com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationSocialContentSeeder;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementPlan;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementPlanner;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementSeeder;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementTargetCatalog;
import com.berkayb.soundconnect.tools.simulation.seed.follow.SimulationFollowPlan;
import com.berkayb.soundconnect.tools.simulation.seed.follow.SimulationFollowPlanner;
import com.berkayb.soundconnect.tools.simulation.seed.follow.SimulationFollowSeeder;
import com.berkayb.soundconnect.tools.simulation.seed.media.SimulationMediaSeedResult;
import com.berkayb.soundconnect.tools.simulation.seed.media.SimulationMediaSeeder;
import com.berkayb.soundconnect.tools.simulation.seed.profile.SimulationProfileMaterializationResult;
import com.berkayb.soundconnect.tools.simulation.seed.profile.SimulationProfileMaterializer;
import com.berkayb.soundconnect.tools.simulation.seed.reset.SimulationDatabaseResetter;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationPreflightResult;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationWorldPreflight;
import com.berkayb.soundconnect.tools.simulation.state.SimulationWorldState;
import com.berkayb.soundconnect.tools.simulation.state.SimulationWorldStateStore;
import com.berkayb.soundconnect.tools.simulation.verify.SimulationMusicianFeedVerifier;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Materializes the versioned local world after production catalogs and roles are ready.
 *
 * <p>Every mutation is delegated to a production application service. This runner owns only
 * ordering, fail-closed mode semantics, logical-id plumbing and credential-free audit reporting.</p>
 */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 200)
@RequiredArgsConstructor
@Slf4j
public final class SimulationBootstrapRunner implements ApplicationRunner {

	private final SimulationRuntimeGuard runtimeGuard;
	private final SimulationWorldLease worldLease;
	private final SimulationProperties properties;
	private final SimulationRuntimeStatus runtimeStatus;
	private final SimulationWorldManifestLoader manifestLoader;
	private final SimulationWorldPreflight preflight;
	private final SimulationDatabaseResetter databaseResetter;
	private final SimulationWorldStateStore worldStateStore;
	private final SimulationControlAdminProvisioner controlAdminProvisioner;
	private final SimulationAccountRegistrationOrchestrator accountRegistration;
	private final SimulationProfileMaterializer profileMaterializer;
	private final SimulationBandMaterializer bandMaterializer;
	private final SimulationMediaSeeder mediaSeeder;
	private final SimulationFollowPlanner followPlanner;
	private final SimulationFollowSeeder followSeeder;
	private final SimulationOpportunityPlanner opportunityPlanner;
	private final SimulationOpportunitySeeder opportunitySeeder;
	private final SimulationSocialContentSeeder socialContentSeeder;
	private final SimulationEngagementTargetCatalog engagementTargetCatalog;
	private final SimulationEngagementPlanner engagementPlanner;
	private final SimulationEngagementSeeder engagementSeeder;
	private final SimulationMusicianFeedVerifier feedVerifier;
	private final SimulationPopulationEngine populationEngine;
	private final SimulationBehaviorProperties behaviorProperties;
	private final SimulationRunReportWriter reportWriter;
	private final Clock clock;

	@Override
	public void run(ApplicationArguments arguments) {
		if (properties.getMode() == SimulationMode.PAUSE) {
			runtimeGuard.assertRuntimeAllowed();
			runtimeStatus.paused();
			log.info("SoundConnect local simulation is PAUSED");
			return;
		}

		SimulationWorldManifest manifest = null;
		SimulationRunLedger ledger = null;
		RuntimeException failure = null;
		try {
			runtimeGuard.assertRuntimeAllowed();
			worldLease.acquire();
			manifest = manifestLoader.loadDefault();
			ledger = new SimulationRunLedger(manifest.worldId(), manifest.seed(), clock);
			registerPlannedMetrics(manifest, ledger);
			materialize(manifest, ledger);
			reportWriter.write(properties.getReportDirectory(), ledger.snapshot());
			runtimeStatus.ready(manifest.worldId());
			log.info("SoundConnect local simulation is READY mode={} world={}",
					properties.getMode(), manifest.worldId());
		} catch (RuntimeException exception) {
			failure = exception;
			String worldId = manifest == null ? null : manifest.worldId();
			if (ledger != null) {
				ledger.failed("bootstrap", "ABORT", null, null, exception);
				try {
					reportWriter.write(properties.getReportDirectory(), ledger.snapshot());
				} catch (RuntimeException reportFailure) {
					exception.addSuppressed(reportFailure);
				}
			}
			runtimeStatus.failed(worldId, runtimeStatus.snapshot().phase(), exception);
			log.error("SoundConnect local simulation failed mode={} phase={} exceptionType={}",
					properties.getMode(), runtimeStatus.snapshot().phase(),
					exception.getClass().getSimpleName());
		}
		if (failure != null) throw failure;
	}

	private void materialize(SimulationWorldManifest manifest, SimulationRunLedger ledger) {
		phase(manifest, "preflight");
		SimulationPreflightResult checked = preflight.verify(manifest);
		ledger.succeeded("bootstrap", "PREFLIGHT", null, null, "runtime and catalogs verified");

		if (properties.getMode() == SimulationMode.FRESH) {
			phase(manifest, "fresh-reset");
			databaseResetter.resetApplicationData();
			ledger.succeeded("bootstrap", "RESET_APPLICATION_DATA", null, null,
					"guarded disposable database reset completed");
		}

		phase(manifest, "world-state");
		SimulationWorldState state = worldStateStore.initializeOrLoad(manifest);
		boolean allowExisting = properties.getMode() != SimulationMode.FRESH;

		phase(manifest, "accounts");
		SimulationControlAdmin controlAdmin = controlAdminProvisioner.provision();
		ledger.succeeded("accounts", "CONTROL_ADMIN", null, null,
				controlAdmin.created() ? "created" : "already present");
		Map<String, UUID> accountIds = materializeAccounts(
				manifest, checked, controlAdmin, allowExisting, ledger);

		phase(manifest, "profiles");
		SimulationProfileMaterializationResult profiles =
				profileMaterializer.materialize(manifest, accountIds, ledger);
		Map<String, UUID> profileIds = profileIds(profiles);
		Map<String, UUID> venueIds = venueIds(profiles);

		phase(manifest, "bands");
		SimulationBandMaterializationResult bands = bandMaterializer.materialize(manifest, accountIds, ledger);
		Map<String, UUID> bandIds = bandIds(bands);

		phase(manifest, "media");
		SimulationMediaSeedResult media = mediaSeeder.seedOrResume(
				manifest, accountIds, profileIds, bandIds, properties.getMode());
		ledger.succeeded("media", "MATERIALIZE", null, null,
				media.createdTrackIds().size() + " tracks, "
						+ media.profilePublicationIds().size() + " profile publications");
		profileMaterializer.applyProfilePictures(
				manifest, accountIds, profiles,
				accountAvatarIds(manifest, media.avatarAssetIds()), ledger);

		phase(manifest, "follow-graph");
		SimulationFollowPlan followPlan = followPlanner.plan(manifest);
		followSeeder.seed(followPlan, accountIds, ledger);

		phase(manifest, "opportunities");
		SimulationOpportunityWindow opportunityWindow = new SimulationOpportunityWindow(
				state.collabAnchor(), state.eventAnchor());
		SimulationOpportunityPlan opportunityPlan = opportunityPlanner.plan(manifest, opportunityWindow);
		SimulationOpportunitySeedResult opportunities = opportunitySeeder.seed(
				manifest, accountIds, profileIds, venueIds, bandIds, checked,
				opportunityWindow, allowExisting, ledger);

		phase(manifest, "social-content");
		SimulationSocialContentSeedResult social = socialContentSeeder.seed(
				manifest, accountIds, checked, opportunityPlan, opportunities, ledger);

		phase(manifest, "engagement");
		var targets = engagementTargetCatalog.eventsAndSocial(
				manifest, accountIds, opportunityPlan, opportunities, social);
		targets = engagementTargetCatalog.append(
				targets, engagementTargetCatalog.media(manifest, accountIds, media));
		SimulationEngagementPlan baseline = engagementPlanner.plan(manifest, accountIds, targets);
		engagementSeeder.seed(baseline, ledger);

		phase(manifest, "feed-verification");
		feedVerifier.verify(manifest, accountIds, ledger);

		phase(manifest, "behavior");
		int fullLikes = Math.max(baseline.likes().size(), behaviorProperties.getPlannedLikes());
		int fullComments = Math.max(baseline.comments().size(), behaviorProperties.getPlannedComments());
		SimulationEngagementPlan fullPlan = engagementPlanner.plan(
				manifest, accountIds, targets, fullLikes, fullComments);
		assertBaselinePrefix(baseline, fullPlan);
		populationEngine.initialize(
				manifest, fullPlan, ledger, baseline.likes().size(), baseline.comments().size());
		if (properties.getMode() == SimulationMode.FAST_FORWARD) {
			int actions = populationEngine.fastForwardConfiguredWindow();
			ledger.succeeded("behavior", "FAST_FORWARD", null, null,
					actions + " actions executed");
		}
	}

	private static void assertBaselinePrefix(
			SimulationEngagementPlan baseline,
			SimulationEngagementPlan fullPlan
	) {
		if (baseline.likes().size() > fullPlan.likes().size()
				|| baseline.comments().size() > fullPlan.comments().size()
				|| !baseline.likes().equals(fullPlan.likes().subList(0, baseline.likes().size()))
				|| !baseline.comments().equals(fullPlan.comments().subList(0, baseline.comments().size()))) {
			throw new IllegalStateException(
					"Behavior plan does not preserve the materialized engagement baseline");
		}
	}

	private Map<String, UUID> materializeAccounts(
			SimulationWorldManifest manifest,
			SimulationPreflightResult checked,
			SimulationControlAdmin controlAdmin,
			boolean allowExisting,
			SimulationRunLedger ledger
	) {
		Map<String, UUID> result = new LinkedHashMap<>();
		for (SimulationWorldManifest.Account account : manifest.accounts()) {
			try {
				SimulationRegisteredAccount registered = accountRegistration.materialize(
						account,
						checked.locationsByAccountKey().get(account.key()),
						properties.getCommonPassword(),
						controlAdmin,
						allowExisting);
				if (result.putIfAbsent(account.key(), registered.userId()) != null) {
					throw new IllegalStateException("Duplicate materialized simulation account key");
				}
				ledger.succeeded("accounts", "MATERIALIZE", account.key(), null,
						registered.created() ? "created" : "resumed");
			} catch (RuntimeException exception) {
				ledger.failed("accounts", "MATERIALIZE", account.key(), null, exception);
				throw exception;
			}
		}
		if (result.size() != manifest.accounts().size()) {
			throw new IllegalStateException("Not every simulation account was materialized");
		}
		return Map.copyOf(result);
	}

	private void phase(SimulationWorldManifest manifest, String phase) {
		runtimeStatus.phase(manifest.worldId(), phase);
		log.info("SoundConnect local simulation phase={} mode={}", phase, properties.getMode());
	}

	private static Map<String, UUID> profileIds(SimulationProfileMaterializationResult profiles) {
		Map<String, UUID> result = new LinkedHashMap<>();
		profiles.profilesByAccountKey().forEach((key, value) -> {
			if (value.profileId() != null) result.put(key, value.profileId());
		});
		return Map.copyOf(result);
	}

	private static Map<String, UUID> venueIds(SimulationProfileMaterializationResult profiles) {
		Map<String, UUID> result = new LinkedHashMap<>();
		profiles.profilesByAccountKey().forEach((key, value) -> {
			if (value.venueAggregateId() != null) result.put(key, value.venueAggregateId());
		});
		return Map.copyOf(result);
	}

	private static Map<String, UUID> bandIds(SimulationBandMaterializationResult bands) {
		Map<String, UUID> result = new LinkedHashMap<>();
		bands.bandsByKey().forEach((key, value) -> result.put(key, value.bandId()));
		return Map.copyOf(result);
	}

	private static Map<String, UUID> accountAvatarIds(
			SimulationWorldManifest manifest,
			Map<String, UUID> allAvatarIds
	) {
		Map<String, UUID> result = new LinkedHashMap<>();
		for (SimulationWorldManifest.Account account : manifest.accounts()) {
			UUID assetId = allAvatarIds.get(account.key());
			if (assetId != null) result.put(account.key(), assetId);
		}
		return Map.copyOf(result);
	}

	private static void registerPlannedMetrics(
			SimulationWorldManifest manifest,
			SimulationRunLedger ledger
	) {
		SimulationWorldManifest.ContentTargets target = manifest.contentTargets();
		ledger.plan("accounts", manifest.accounts().size());
		ledger.plan("bands", manifest.bands().size());
		ledger.plan("collabs", target.collabListings());
		ledger.plan("events", target.events());
		ledger.plan("tracks", target.tracks());
		ledger.plan("profile-media", target.profileMediaPublications());
		ledger.plan("overthinking-profile-shares", target.overthinkingProfileShares());
		ledger.plan("table-group-profile-shares", target.tableGroupProfileShares());
		ledger.plan("listener-event-publications", target.listenerEventProfilePublications());
		ledger.plan("follows", target.follows());
		ledger.plan("baseline-likes", target.likes());
		ledger.plan("baseline-comments", target.comments());
	}
}
