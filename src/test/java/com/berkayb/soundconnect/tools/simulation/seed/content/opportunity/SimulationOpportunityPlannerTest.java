package com.berkayb.soundconnect.tools.simulation.seed.content.opportunity;

import com.berkayb.soundconnect.modules.collab.enums.CollabCadence;
import com.berkayb.soundconnect.modules.collab.enums.CollabWantedType;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.CollabFinalState;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventFinalState;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlan.EventPerformerStory;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.InstitutionState;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class SimulationOpportunityPlannerTest {

	private final SimulationOpportunityPlanner planner = new SimulationOpportunityPlanner();
	private final SimulationWorldManifest manifest = new SimulationWorldManifestLoader(
			new ObjectMapper().findAndRegisterModules()).loadDefault();
	private final SimulationOpportunityWindow window = new SimulationOpportunityWindow(
			Instant.parse("2026-09-11T12:00:00Z"), LocalDate.of(2026, 9, 11));

	@Test
	void createsTheApprovedThirtySixCollabAndThirtyEventStoryMixDeterministically() {
		SimulationOpportunityPlan first = planner.plan(manifest, window);
		SimulationOpportunityPlan second = planner.plan(manifest, window);

		assertThat(first).isEqualTo(second);
		assertThat(first.collabs()).hasSize(36);
		assertThat(first.events()).hasSize(30);
		assertThat(first.collabs()).extracting(SimulationOpportunityPlan.CollabStory::clientRequestId)
				.doesNotHaveDuplicates();
		assertThat(first.collabs()).filteredOn(value -> value.finalState() == CollabFinalState.OPEN)
				.hasSize(30);
		assertThat(first.collabs()).filteredOn(value -> value.finalState() == CollabFinalState.CLOSED)
				.hasSize(4);
		assertThat(first.collabs()).filteredOn(value -> value.finalState() == CollabFinalState.DRAFT)
				.hasSize(2);
		assertThat(first.events()).filteredOn(value -> value.finalState() == EventFinalState.DELETED)
				.hasSize(1);
	}

	@Test
	void collabStoriesCoverEverySupportedPublisherAndRespectProductionFieldRules() {
		SimulationOpportunityPlan plan = planner.plan(manifest, window);

		assertThat(plan.collabs()).extracting(value -> value.publisher().profileType())
				.contains(ProfileType.MUSICIAN, ProfileType.BAND, ProfileType.VENUE, ProfileType.STUDIO);
		assertThat(plan.collabs()).allSatisfy(story -> {
			assertThat(story.title()).hasSizeBetween(5, 100);
			assertThat(story.description()).hasSizeBetween(20, 500);
			assertThat(story.genres()).hasSizeBetween(1, 3);
			if (story.wantedType() == CollabWantedType.MUSICIAN) {
				assertThat((story.instrumentName() == null) ^ (story.branch() == null)).isTrue();
			} else {
				assertThat(story.instrumentName()).isNull();
				assertThat(story.branch()).isNull();
			}
			if (story.cadence() == CollabCadence.EXTRA) {
				assertThat(story.scheduledAt()).isAfter(window.collabAnchor())
						.isBeforeOrEqualTo(window.collabAnchor().plusSeconds(7 * 24 * 60 * 60));
			} else {
				assertThat(story.scheduledAt()).isNull();
				if (story.feeAmountMinor() != null) {
					assertThat(story.publisher().profileType()).isEqualTo(ProfileType.VENUE);
				}
			}
			assertThat(story.currency()).isEqualTo(story.feeAmountMinor() == null ? null : "TRY");
		});
	}

	@Test
	void eventCreatorsAreOnlyVerifiedApprovedVenuesNeverMusicians() {
		SimulationOpportunityPlan plan = planner.plan(manifest, window);
		Set<String> approvedVenueKeys = manifest.accounts().stream()
				.filter(account -> account.role() == AccountRole.VENUE)
				.filter(account -> account.institutionState() == InstitutionState.APPROVED)
				.map(SimulationWorldManifest.Account::key)
				.collect(Collectors.toSet());
		Set<String> musicianKeys = manifest.accounts().stream()
				.filter(account -> account.role() == AccountRole.MUSICIAN)
				.map(SimulationWorldManifest.Account::key)
				.collect(Collectors.toSet());

		assertThat(plan.events()).allSatisfy(event -> {
			assertThat(event.venueAccountKey()).isIn(approvedVenueKeys);
			assertThat(event.venueAccountKey()).isNotIn(musicianKeys);
		});
		assertThat(plan.events()).filteredOn(value -> value.eventDate().isBefore(window.eventAnchor()))
				.hasSize(3);
		assertThat(plan.events()).filteredOn(value -> value.eventDate().isAfter(window.eventAnchor()))
				.hasSize(27);
		assertThat(plan.events()).filteredOn(value -> value.performerStory()
				== EventPerformerStory.MUSICIAN_PENDING).hasSize(1);
		assertThat(plan.events()).filteredOn(value -> value.performerStory()
				== EventPerformerStory.BAND_PENDING).hasSize(1);
		assertThat(plan.events()).filteredOn(value -> value.performerStory()
				== EventPerformerStory.MUSICIAN_ACCEPTED).hasSize(1);
	}
}
