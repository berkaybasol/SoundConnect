package com.berkayb.soundconnect.tools.simulation;

import com.berkayb.soundconnect.tools.simulation.behavior.SimulationBehaviorProperties;
import com.berkayb.soundconnect.tools.simulation.behavior.SimulationPopulationEngine;
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunReportWriter;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationRuntimeStatus;
import com.berkayb.soundconnect.tools.simulation.runtime.SimulationWorldLease;
import com.berkayb.soundconnect.tools.simulation.seed.account.SimulationAccountRegistrationOrchestrator;
import com.berkayb.soundconnect.tools.simulation.seed.account.SimulationControlAdminProvisioner;
import com.berkayb.soundconnect.tools.simulation.seed.band.SimulationBandMaterializer;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunityPlanner;
import com.berkayb.soundconnect.tools.simulation.seed.content.opportunity.SimulationOpportunitySeeder;
import com.berkayb.soundconnect.tools.simulation.seed.content.social.SimulationSocialContentSeeder;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementPlanner;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementSeeder;
import com.berkayb.soundconnect.tools.simulation.seed.engagement.SimulationEngagementTargetCatalog;
import com.berkayb.soundconnect.tools.simulation.seed.follow.SimulationFollowPlanner;
import com.berkayb.soundconnect.tools.simulation.seed.follow.SimulationFollowSeeder;
import com.berkayb.soundconnect.tools.simulation.seed.media.SimulationMediaSeeder;
import com.berkayb.soundconnect.tools.simulation.seed.profile.SimulationProfileMaterializer;
import com.berkayb.soundconnect.tools.simulation.seed.reset.SimulationDatabaseResetter;
import com.berkayb.soundconnect.tools.simulation.seed.support.SimulationWorldPreflight;
import com.berkayb.soundconnect.tools.simulation.state.SimulationWorldStateStore;
import com.berkayb.soundconnect.tools.simulation.verify.SimulationMusicianFeedVerifier;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifestLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationBootstrapRunnerTest {

	@Mock private SimulationRuntimeGuard runtimeGuard;
	@Mock private SimulationWorldLease worldLease;
	@Mock private SimulationProperties properties;
	@Mock private SimulationRuntimeStatus runtimeStatus;
	@Mock private SimulationWorldManifestLoader manifestLoader;
	@Mock private SimulationWorldPreflight preflight;
	@Mock private SimulationDatabaseResetter databaseResetter;
	@Mock private SimulationWorldStateStore worldStateStore;
	@Mock private SimulationControlAdminProvisioner controlAdminProvisioner;
	@Mock private SimulationAccountRegistrationOrchestrator accountRegistration;
	@Mock private SimulationProfileMaterializer profileMaterializer;
	@Mock private SimulationBandMaterializer bandMaterializer;
	@Mock private SimulationMediaSeeder mediaSeeder;
	@Mock private SimulationFollowPlanner followPlanner;
	@Mock private SimulationFollowSeeder followSeeder;
	@Mock private SimulationOpportunityPlanner opportunityPlanner;
	@Mock private SimulationOpportunitySeeder opportunitySeeder;
	@Mock private SimulationSocialContentSeeder socialContentSeeder;
	@Mock private SimulationEngagementTargetCatalog engagementTargetCatalog;
	@Mock private SimulationEngagementPlanner engagementPlanner;
	@Mock private SimulationEngagementSeeder engagementSeeder;
	@Mock private SimulationMusicianFeedVerifier feedVerifier;
	@Mock private SimulationPopulationEngine populationEngine;
	@Mock private SimulationBehaviorProperties behaviorProperties;
	@Mock private SimulationRunReportWriter reportWriter;
	@Mock private Clock clock;

	@InjectMocks private SimulationBootstrapRunner runner;

	@Test
	void pauseIsVisibleAndPerformsNoMaterialization() {
		when(properties.getMode()).thenReturn(SimulationMode.PAUSE);

		runner.run(null);

		verify(runtimeGuard).assertRuntimeAllowed();
		verify(runtimeStatus).paused();
		verifyNoInteractions(worldLease, manifestLoader, preflight, databaseResetter, worldStateStore,
				controlAdminProvisioner, accountRegistration, profileMaterializer,
				bandMaterializer, mediaSeeder, followPlanner, followSeeder,
				opportunityPlanner, opportunitySeeder, socialContentSeeder,
				engagementTargetCatalog, engagementPlanner, engagementSeeder,
				feedVerifier, populationEngine, behaviorProperties, reportWriter, clock);
	}
}
