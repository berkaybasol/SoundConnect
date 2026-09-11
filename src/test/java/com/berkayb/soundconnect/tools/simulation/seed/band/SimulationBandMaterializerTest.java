package com.berkayb.soundconnect.tools.simulation.seed.band;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandCreateRequestDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.request.BandMemberTitleUpdateDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandMemberResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandPendingInvitationResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandReceivedInvitationResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandResponseDto;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandService;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.berkayb.soundconnect.tools.simulation.SimulationRuntimeGuard;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Account;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.AccountRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.Band;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.BandMember;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.BandMemberRole;
import com.berkayb.soundconnect.tools.simulation.world.SimulationWorldManifest.EmailVerificationState;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimulationBandMaterializerTest {
	@Mock private SimulationRuntimeGuard runtimeGuard;
	@Mock private Validator validator;
	@Mock private BandService bandService;

	private SimulationBandMaterializer materializer;

	@BeforeEach
	void setUp() {
		materializer = new SimulationBandMaterializer(runtimeGuard, validator, bandService);
	}

	@Test
	void createsInvitesAcceptsAndMapsManifestManagerToMemberTitle() {
		Account founder = musician("founder", EmailVerificationState.VERIFIED);
		Account manager = musician("manager", EmailVerificationState.VERIFIED);
		Band requested = band(founder.key(), manager.key(), BandMemberRole.MANAGER);
		UUID founderId = UUID.randomUUID();
		UUID managerId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		UUID invitationId = UUID.randomUUID();
		BandMemberResponseDto founderMember = member(founderId, "FOUNDER", null, 0);
		BandMemberResponseDto managerUntitled = member(managerId, "MEMBER", null, 1);
		BandMemberResponseDto managerTitled = member(managerId, "MEMBER", "Menajer", 2);
		BandResponseDto created = response(requested, bandId, Set.of(founderMember));
		BandResponseDto accepted = response(requested, bandId, Set.of(founderMember, managerUntitled));
		BandResponseDto completed = response(requested, bandId, Set.of(founderMember, managerTitled));
		when(bandService.getBandsByUser(founderId)).thenReturn(List.of());
		when(bandService.createBand(eq(founderId), any())).thenReturn(created);
		when(bandService.getPendingInvitations(bandId, founderId, 0, 50)).thenReturn(emptyPage());
		when(bandService.getCurrentReceivedInvitation(bandId, managerId)).thenReturn(
				new BandReceivedInvitationResponseDto(
						bandId, requested.name(), null, "PENDING", invitationId));
		when(bandService.getPublicBandById(bandId)).thenReturn(accepted, completed);
		when(bandService.updateMemberTitle(eq(bandId), eq(founderId), eq(managerId), any()))
				.thenReturn(managerTitled);
		Map<String, UUID> ids = linkedIds(founder.key(), founderId, manager.key(), managerId);

		SimulationBandReference result = materializer.materialize(
				manifest(List.of(founder, manager), List.of(requested)), ids).require(requested.key());

		assertThat(result.bandId()).isEqualTo(bandId);
		verify(bandService).inviteMember(bandId, founderId, managerId,
				"SoundConnect yerel simülasyon grubu daveti");
		verify(bandService).acceptInvite(bandId, managerId, invitationId);
		ArgumentCaptor<BandMemberTitleUpdateDto> title =
				ArgumentCaptor.forClass(BandMemberTitleUpdateDto.class);
		verify(bandService).updateMemberTitle(eq(bandId), eq(founderId), eq(managerId), title.capture());
		assertThat(title.getValue()).isEqualTo(new BandMemberTitleUpdateDto("Menajer", 1L));
		verify(validator).validate(any(BandCreateRequestDto.class));
		verify(validator).validate(any(BandMemberTitleUpdateDto.class));
		verify(runtimeGuard).assertRuntimeAllowed();
	}

	@Test
	void exactExistingBandResumesWithoutMutation() {
		Account founder = musician("founder", EmailVerificationState.VERIFIED);
		Account member = musician("member", EmailVerificationState.VERIFIED);
		Band requested = band(founder.key(), member.key(), BandMemberRole.MEMBER);
		UUID founderId = UUID.randomUUID();
		UUID memberId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		BandResponseDto existing = response(requested, bandId, Set.of(
				member(founderId, "FOUNDER", null, 0),
				member(memberId, "MEMBER", null, 1)));
		when(bandService.getBandsByUser(founderId)).thenReturn(List.of(existing));
		when(bandService.getPendingInvitations(bandId, founderId, 0, 50)).thenReturn(emptyPage());
		when(bandService.getPublicBandById(bandId)).thenReturn(existing);

		SimulationBandReference result = materializer.materialize(
				manifest(List.of(founder, member), List.of(requested)),
				linkedIds(founder.key(), founderId, member.key(), memberId)).require(requested.key());

		assertThat(result.bandId()).isEqualTo(bandId);
		verify(bandService, never()).createBand(any(), any());
		verify(bandService, never()).inviteMember(any(), any(), any(), any());
		verify(bandService, never()).acceptInvite(any(), any(), any());
		verify(bandService, never()).updateMemberTitle(any(), any(), any(), any());
		verify(bandService, times(2)).getPendingInvitations(bandId, founderId, 0, 50);
	}

	@Test
	void resumesAnExistingPendingInvitationInsteadOfReinviting() {
		Account founder = musician("founder", EmailVerificationState.VERIFIED);
		Account member = musician("member", EmailVerificationState.VERIFIED);
		Band requested = band(founder.key(), member.key(), BandMemberRole.MEMBER);
		UUID founderId = UUID.randomUUID();
		UUID memberId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		UUID invitationId = UUID.randomUUID();
		BandMemberResponseDto founderMember = member(founderId, "FOUNDER", null, 0);
		BandResponseDto partial = response(requested, bandId, Set.of(founderMember));
		BandResponseDto completed = response(requested, bandId, Set.of(
				founderMember, member(memberId, "MEMBER", null, 1)));
		when(bandService.getBandsByUser(founderId)).thenReturn(List.of(partial));
		when(bandService.getPendingInvitations(bandId, founderId, 0, 50)).thenReturn(
				page(List.of(new BandPendingInvitationResponseDto(
						memberId, "member", null, "PENDING"))),
				emptyPage());
		when(bandService.getCurrentReceivedInvitation(bandId, memberId)).thenReturn(
				new BandReceivedInvitationResponseDto(
						bandId, requested.name(), null, "PENDING", invitationId));
		when(bandService.getPublicBandById(bandId)).thenReturn(completed);

		materializer.materialize(
				manifest(List.of(founder, member), List.of(requested)),
				linkedIds(founder.key(), founderId, member.key(), memberId));

		verify(bandService, never()).inviteMember(any(), any(), any(), any());
		verify(bandService).acceptInvite(bandId, memberId, invitationId);
	}

	@Test
	void failsClosedWhenExistingBandHasUnexpectedActiveMember() {
		Account founder = musician("founder", EmailVerificationState.VERIFIED);
		Account member = musician("member", EmailVerificationState.VERIFIED);
		Band requested = band(founder.key(), member.key(), BandMemberRole.MEMBER);
		UUID founderId = UUID.randomUUID();
		UUID memberId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		BandResponseDto conflicting = response(requested, bandId, Set.of(
				member(founderId, "FOUNDER", null, 0),
				member(UUID.randomUUID(), "MEMBER", null, 1)));
		when(bandService.getBandsByUser(founderId)).thenReturn(List.of(conflicting));

		assertThatThrownBy(() -> materializer.materialize(
				manifest(List.of(founder, member), List.of(requested)),
				linkedIds(founder.key(), founderId, member.key(), memberId)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("unexpected active member");
		verify(bandService, never()).inviteMember(any(), any(), any(), any());
		verify(bandService, never()).acceptInvite(any(), any(), any());
	}

	@Test
	void failsClosedWhenExistingManagerUsesAuthorizationRoleInsteadOfDisplayTitle() {
		Account founder = musician("founder", EmailVerificationState.VERIFIED);
		Account manager = musician("manager", EmailVerificationState.VERIFIED);
		Band requested = band(founder.key(), manager.key(), BandMemberRole.MANAGER);
		UUID founderId = UUID.randomUUID();
		UUID managerId = UUID.randomUUID();
		BandResponseDto conflicting = response(requested, UUID.randomUUID(), Set.of(
				member(founderId, "FOUNDER", null, 0),
				member(managerId, "MANAGER", null, 1)));
		when(bandService.getBandsByUser(founderId)).thenReturn(List.of(conflicting));

		assertThatThrownBy(() -> materializer.materialize(
				manifest(List.of(founder, manager), List.of(requested)),
				linkedIds(founder.key(), founderId, manager.key(), managerId)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("membership role conflicts");
		verify(bandService, never()).updateMemberTitle(any(), any(), any(), any());
	}

	@Test
	void explicitJakartaValidationRunsBeforeBandCreation() {
		Account founder = musician("founder", EmailVerificationState.VERIFIED);
		Band requested = new Band(
				"band-test", "Test Grubu", "Birlikte üreten simülasyon grubu.", "Test",
				SimulationWorldManifest.Scene.ISTANBUL_ALTERNATIVE_ROCK, founder.key(),
				List.of(new BandMember(founder.key(), BandMemberRole.FOUNDER)));
		UUID founderId = UUID.randomUUID();
		when(bandService.getBandsByUser(founderId)).thenReturn(List.of());
		@SuppressWarnings("unchecked")
		ConstraintViolation<BandCreateRequestDto> violation =
				org.mockito.Mockito.mock(ConstraintViolation.class);
		when(validator.validate(any(BandCreateRequestDto.class))).thenReturn(Set.of(violation));

		assertThatThrownBy(() -> materializer.materialize(
				manifest(List.of(founder), List.of(requested)), Map.of(founder.key(), founderId)))
				.isInstanceOf(ConstraintViolationException.class)
				.hasMessageContaining(requested.key());
		verify(bandService, never()).createBand(any(), any());
	}

	@Test
	void rejectsUnverifiedBandMemberBeforeCallingBandService() {
		Account founder = musician("founder", EmailVerificationState.VERIFIED);
		Account member = musician("unverified", EmailVerificationState.UNVERIFIED);
		Band requested = band(founder.key(), member.key(), BandMemberRole.MEMBER);

		assertThatThrownBy(() -> materializer.materialize(
				manifest(List.of(founder, member), List.of(requested)),
				linkedIds(founder.key(), UUID.randomUUID(), member.key(), UUID.randomUUID())))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("verified musicians");
		verify(bandService, never()).getBandsByUser(any());
	}

	private Account musician(String key, EmailVerificationState verification) {
		return new Account(
				key, AccountRole.MUSICIAN, SimulationWorldManifest.Scene.ISTANBUL_ALTERNATIVE_ROCK,
				key, key + "@soundconnect.invalid", verification, "Test", "Müzisyen",
				"Test Müzisyen", "Yerel simülasyon müzisyeni.", "Test",
				List.of("Vokal"),
				new SimulationWorldManifest.MusicianProfilePlan(true, true, true, true, true, true),
				SimulationWorldManifest.ObserverProfile.NONE, null, null,
				new SimulationWorldManifest.Location("İstanbul", "Kadıköy", "Caferağa", null), null);
	}

	private Band band(String founderKey, String memberKey, BandMemberRole memberRole) {
		return new Band(
				"band-test", "Test Grubu", "Birlikte üreten simülasyon grubu.", "Test",
				SimulationWorldManifest.Scene.ISTANBUL_ALTERNATIVE_ROCK, founderKey,
				List.of(
						new BandMember(founderKey, BandMemberRole.FOUNDER),
						new BandMember(memberKey, memberRole)));
	}

	private SimulationWorldManifest manifest(List<Account> accounts, List<Band> bands) {
		return new SimulationWorldManifest(1, "test-world", 42L, List.of(),
				new SimulationWorldManifest.ContentTargets(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
				accounts, bands);
	}

	private BandResponseDto response(Band requested, UUID bandId, Set<BandMemberResponseDto> members) {
		return new BandResponseDto(
				bandId, requested.name(), requested.bio(), null, null,
				null, null, null, null, null, List.of(), members, null);
	}

	private BandMemberResponseDto member(UUID userId, String role, String title, long titleVersion) {
		return new BandMemberResponseDto(
				userId, "member", null, role, "ACTIVE", title, titleVersion);
	}

	private PageResponse<BandPendingInvitationResponseDto> emptyPage() {
		return new PageResponse<>(List.of(), 0, 50, 0, 0, true, true);
	}

	private PageResponse<BandPendingInvitationResponseDto> page(
			List<BandPendingInvitationResponseDto> content) {
		return new PageResponse<>(content, 0, 50, content.size(), 1, true, true);
	}

	private Map<String, UUID> linkedIds(Object... values) {
		LinkedHashMap<String, UUID> result = new LinkedHashMap<>();
		for (int index = 0; index < values.length; index += 2) {
			result.put((String) values[index], (UUID) values[index + 1]);
		}
		return result;
	}
}
