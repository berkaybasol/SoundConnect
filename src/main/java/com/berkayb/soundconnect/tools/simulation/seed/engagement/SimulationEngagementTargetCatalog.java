package com.berkayb.soundconnect.tools.simulation.seed.engagement;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventFinalState;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunitySeedResult;
import com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationEngagementTarget;
import com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationSocialContentSeedResult;
import com.berkayb.soundconnect.tools.simulation.seed.media.SimulationMediaSeedResult;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Combines only public, production-readable targets from completed seed phases. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
public final class SimulationEngagementTargetCatalog {

	public List<SimulationEngagementTarget> eventsAndSocial(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			SimulationOpportunityPlan opportunityPlan,
			SimulationOpportunitySeedResult opportunityResult,
			SimulationSocialContentSeedResult socialResult
	) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(accountUserIds, "accountUserIds");
		Objects.requireNonNull(opportunityPlan, "opportunityPlan");
		Objects.requireNonNull(opportunityResult, "opportunityResult");
		Objects.requireNonNull(socialResult, "socialResult");
		Map<String, Account> accounts = new LinkedHashMap<>();
		for (Account account : manifest.accounts()) accounts.put(account.key(), account);

		List<SimulationEngagementTarget> result = new ArrayList<>(socialResult.engagementTargets());
		for (var story : opportunityPlan.events()) {
			if (story.finalState() != EventFinalState.VISIBLE) continue;
			UUID eventId = opportunityResult.events().ids().get(story.key());
			if (eventId == null) {
				throw new IllegalStateException("Visible Event has no materialized id: " + story.key());
			}
			Account venue = accounts.get(story.venueAccountKey());
			if (venue == null) throw new IllegalStateException("Event venue is absent: " + story.key());
			UUID ownerId = accountUserIds.get(venue.key());
			if (ownerId == null) throw new IllegalStateException("Event owner is absent: " + story.key());
			result.add(new SimulationEngagementTarget(
					story.key(), EngagementTargetType.EVENT, eventId,
					venue.key(), ownerId, venue.scene()));
		}
		return validateAndSort(result);
	}

	public List<SimulationEngagementTarget> append(
			List<SimulationEngagementTarget> base,
			List<SimulationEngagementTarget> additional
	) {
		Objects.requireNonNull(base, "base");
		Objects.requireNonNull(additional, "additional");
		List<SimulationEngagementTarget> values = new ArrayList<>(base.size() + additional.size());
		values.addAll(base);
		values.addAll(additional);
		return validateAndSort(values);
	}

	public List<SimulationEngagementTarget> media(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			SimulationMediaSeedResult mediaResult
	) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(accountUserIds, "accountUserIds");
		Objects.requireNonNull(mediaResult, "mediaResult");
		Map<String, Account> accounts = new LinkedHashMap<>();
		for (Account account : manifest.accounts()) accounts.put(account.key(), account);
		Map<String, SimulationWorldManifest.Band> bands = new LinkedHashMap<>();
		for (var band : manifest.bands()) bands.put(band.key(), band);

		List<SimulationEngagementTarget> result = new ArrayList<>();
		List<SimulationMediaSeedResult.MediaTarget> mediaTargets = new ArrayList<>(
				mediaResult.profileMediaTargets());
		mediaTargets.addAll(mediaResult.trackMediaTargets());
		for (SimulationMediaSeedResult.MediaTarget media : mediaTargets) {
			Account owner = accounts.get(media.ownerKey());
			String ownerAccountKey;
			if (owner != null) {
				ownerAccountKey = owner.key();
			} else {
				SimulationWorldManifest.Band band = bands.get(media.ownerKey());
				if (band == null) {
					throw new IllegalStateException("Media owner is absent from the world: " + media.ownerKey());
				}
				ownerAccountKey = band.ownerAccountKey();
				owner = accounts.get(ownerAccountKey);
			}
			if (owner == null) throw new IllegalStateException("Media owner account is absent: " + media.ownerKey());
			UUID ownerUserId = accountUserIds.get(ownerAccountKey);
			if (ownerUserId == null) throw new IllegalStateException("Media owner user is absent: " + media.ownerKey());
			result.add(new SimulationEngagementTarget(
					media.logicalKey(), EngagementTargetType.MEDIA, media.assetId(),
					ownerAccountKey, ownerUserId, owner.scene()));
		}
		return validateAndSort(result);
	}

	private static List<SimulationEngagementTarget> validateAndSort(
			List<SimulationEngagementTarget> values
	) {
		Set<String> keys = new HashSet<>();
		Set<String> targets = new HashSet<>();
		for (SimulationEngagementTarget value : values) {
			if (value == null) throw new IllegalArgumentException("Engagement target cannot be null");
			if (!keys.add(value.logicalKey())) {
				throw new IllegalStateException("Duplicate engagement logical key: " + value.logicalKey());
			}
			String identity = value.targetType() + "|" + value.targetId();
			if (!targets.add(identity)) {
				throw new IllegalStateException("Duplicate engagement target: " + identity);
			}
		}
		return values.stream().sorted(Comparator.comparing(SimulationEngagementTarget::logicalKey)).toList();
	}
}
