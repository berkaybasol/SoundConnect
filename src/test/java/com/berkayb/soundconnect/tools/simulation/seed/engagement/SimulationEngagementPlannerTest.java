package com.berkayb.soundconnect.tools.simulation.seed.engagement;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationEngagementTarget;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationEngagementPlannerTest {

	private final SimulationEngagementPlanner planner = new SimulationEngagementPlanner();
	private SimulationWorldManifest manifest;
	private Map<String, UUID> userIds;
	private List<SimulationEngagementTarget> targets;

	@BeforeEach
	void setUp() {
		manifest = new SimulationWorldManifestLoader(new ObjectMapper()).loadDefault();
		userIds = new LinkedHashMap<>();
		for (var account : manifest.accounts()) {
			userIds.put(account.key(), stable("user|" + account.key()));
		}
		targets = new ArrayList<>();
		List<EngagementTargetType> types = List.of(
				EngagementTargetType.MEDIA,
				EngagementTargetType.EVENT,
				EngagementTargetType.EVENT_POST,
				EngagementTargetType.TABLE_GROUP_POST,
				EngagementTargetType.OVERTHINKING_PROFILE_SHARE);
		for (int index = 0; index < 10; index++) {
			var owner = manifest.accounts().get(index);
			targets.add(new SimulationEngagementTarget(
					"target-" + index,
					types.get(index % types.size()),
					stable("target|" + index),
					owner.key(),
					userIds.get(owner.key()),
					owner.scene()));
		}
	}

	@Test
	void createsExactStableUniquePlansWithoutObserverOrSelfActions() {
		SimulationEngagementPlan first = planner.plan(manifest, userIds, targets);
		SimulationEngagementPlan second = planner.plan(manifest, userIds, targets);

		assertThat(first).isEqualTo(second);
		assertThat(first.likes()).hasSize(300);
		assertThat(first.comments()).hasSize(100);
		assertThat(first.likes().stream()
				.map(value -> value.actorAccountKey() + "|" + value.target().targetType()
						+ "|" + value.target().targetId()))
				.doesNotHaveDuplicates();
		assertThat(first.comments().stream()
				.map(value -> value.actorAccountKey() + "|" + value.target().targetType()
						+ "|" + value.target().targetId()))
				.doesNotHaveDuplicates();
		assertThat(first.likes()).allMatch(action ->
				!action.actorUserId().equals(action.target().ownerUserId()));
		assertThat(first.comments()).allMatch(action ->
				!action.actorUserId().equals(action.target().ownerUserId())
						&& action.text() != null && !action.text().isBlank());

		Set<String> excluded = manifest.accounts().stream()
				.filter(account -> account.observer() != SimulationWorldManifest.ObserverProfile.NONE
						|| account.emailVerification() != SimulationWorldManifest.EmailVerificationState.VERIFIED
						|| account.listenerVisibility() == SimulationWorldManifest.ListenerVisibilityState.GHOST
						|| account.listenerVisibility() == SimulationWorldManifest.ListenerVisibilityState.VISIBILITY_PENDING
						|| account.institutionState() == SimulationWorldManifest.InstitutionState.PENDING
						|| account.institutionState() == SimulationWorldManifest.InstitutionState.REJECTED)
				.map(SimulationWorldManifest.Account::key)
				.collect(Collectors.toSet());
		assertThat(first.likes()).noneMatch(action -> excluded.contains(action.actorAccountKey()));
		assertThat(first.comments()).noneMatch(action -> excluded.contains(action.actorAccountKey()));
	}

	@Test
	void rejectsDuplicatePublicTargetIdentity() {
		List<SimulationEngagementTarget> invalid = new ArrayList<>(targets);
		SimulationEngagementTarget first = targets.getFirst();
		invalid.add(new SimulationEngagementTarget("another-key", first.targetType(), first.targetId(),
				first.ownerAccountKey(), first.ownerUserId(), first.scene()));

		assertThatThrownBy(() -> planner.plan(manifest, userIds, invalid))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Duplicate engagement target identity");
	}

	private static UUID stable(String value) {
		return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
	}
}
