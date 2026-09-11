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
import com.berkayb.soundconnect.tools.simulation.report.SimulationRunLedger;
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
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Creates and resumes manifest bands through the production invitation/acceptance workflow. */
@Component
@Profile("local & simulation")
@ConditionalOnProperty(prefix = "app.simulation", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class SimulationBandMaterializer {
	private static final String PHASE = "bands";
	private static final String ACTIVE = "ACTIVE";
	private static final String FOUNDER = "FOUNDER";
	private static final String MEMBER = "MEMBER";
	private static final String MANAGER_TITLE = "Menajer";
	private static final int INVITATION_PAGE_SIZE = 50;

	private final SimulationRuntimeGuard runtimeGuard;
	private final Validator validator;
	private final BandService bandService;

	public SimulationBandMaterializationResult materialize(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds
	) {
		return materialize(manifest, accountUserIds, null);
	}

	public SimulationBandMaterializationResult materialize(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds,
			SimulationRunLedger ledger
	) {
		runtimeGuard.assertRuntimeAllowed();
		Preflight preflight = validateInputs(manifest, accountUserIds);
		LinkedHashMap<String, SimulationBandReference> result = new LinkedHashMap<>();
		HashSet<UUID> materializedIds = new HashSet<>();

		for (Band band : manifest.bands()) {
			try {
				UUID ownerId = accountUserIds.get(band.ownerAccountKey());
				BandResponseDto materialized = ensureBand(band, ownerId, accountUserIds);
				if (materialized.id() == null || !materializedIds.add(materialized.id())) {
					throw mismatch(band.key(), "band id is missing or shared by another manifest band");
				}
				result.put(band.key(), new SimulationBandReference(
						band.key(), materialized.id(), band.ownerAccountKey(), ownerId));
				succeeded(ledger, band.key(), "materialized");
			} catch (RuntimeException failure) {
				if (ledger != null) ledger.failed(PHASE, "materialize", band.ownerAccountKey(), band.key(), failure);
				throw failure;
			}
		}
		if (result.size() != preflight.bandKeys().size()) {
			throw new IllegalStateException("Not every simulation band was materialized");
		}
		return new SimulationBandMaterializationResult(result);
	}

	private BandResponseDto ensureBand(
			Band requested,
			UUID ownerId,
			Map<String, UUID> accountUserIds
	) {
		List<BandResponseDto> matching = bandService.getBandsByUser(ownerId).stream()
				.filter(candidate -> requested.name().equals(candidate.name()))
				.toList();
		if (matching.size() > 1) {
			throw mismatch(requested.key(), "owner sees duplicate bands with the manifest name");
		}

		BandResponseDto band;
		boolean created = matching.isEmpty();
		if (created) {
			BandCreateRequestDto create = new BandCreateRequestDto(
					requested.name(), requested.bio(), null,
					null, null, null, null, null, List.of());
			validateDto(create, requested.key());
			band = bandService.createBand(ownerId, create);
		} else {
			band = matching.getFirst();
			assertResumeIdentity(requested, band, accountUserIds);
		}
		if (band == null || band.id() == null) {
			throw mismatch(requested.key(), "create/read did not return a band id");
		}
		assertBandContent(requested, band);

		Map<UUID, BandMemberResponseDto> active = activeMembers(requested, band);
		Set<UUID> expectedIds = requested.members().stream()
				.map(member -> accountUserIds.get(member.accountKey()))
				.collect(Collectors.toUnmodifiableSet());
		Set<UUID> pendingIds = pendingMemberIds(requested, ownerId, band.id());
		if (!expectedIds.containsAll(pendingIds)) {
			throw mismatch(requested.key(), "band has an unexpected pending invitation");
		}

		for (BandMember member : requested.members()) {
			if (member.role() == BandMemberRole.FOUNDER) continue;
			UUID userId = accountUserIds.get(member.accountKey());
			if (!active.containsKey(userId)) {
				BandReceivedInvitationResponseDto invitation;
				if (pendingIds.contains(userId)) {
					invitation = bandService.getCurrentReceivedInvitation(band.id(), userId);
				} else {
					bandService.inviteMember(
							band.id(), ownerId, userId,
							"SoundConnect yerel simülasyon grubu daveti");
					invitation = bandService.getCurrentReceivedInvitation(band.id(), userId);
				}
				assertInvitation(requested, band.id(), invitation);
				bandService.acceptInvite(band.id(), userId, invitation.invitationId());
				band = bandService.getPublicBandById(band.id());
				active = activeMembers(requested, band);
				BandMemberResponseDto accepted = active.get(userId);
				if (accepted == null || !MEMBER.equals(accepted.role())) {
					throw mismatch(requested.key(), "accepted member did not become an active MEMBER");
				}
			}

			BandMemberResponseDto current = active.get(userId);
			if (member.role() == BandMemberRole.MANAGER) {
				if (current.memberTitle() == null || current.memberTitle().isBlank()) {
					BandMemberTitleUpdateDto title = new BandMemberTitleUpdateDto(
							MANAGER_TITLE, current.titleVersion());
					validateDto(title, requested.key());
					BandMemberResponseDto titled = bandService.updateMemberTitle(
							band.id(), ownerId, userId, title);
					assertManagerMember(requested, titled, userId);
				} else if (!MANAGER_TITLE.equals(current.memberTitle())) {
					throw mismatch(requested.key(), "manager display title conflicts with the manifest");
				}
			} else if (current.memberTitle() != null && !current.memberTitle().isBlank()) {
				throw mismatch(requested.key(), "ordinary member has an unexpected display title");
			}
		}

		BandResponseDto completed = bandService.getPublicBandById(band.id());
		assertExactRoster(requested, completed, accountUserIds);
		if (!pendingMemberIds(requested, ownerId, band.id()).isEmpty()) {
			throw mismatch(requested.key(), "band still has pending invitations after materialization");
		}
		return completed;
	}

	private void assertResumeIdentity(
			Band requested,
			BandResponseDto existing,
			Map<String, UUID> accountUserIds
	) {
		assertBandContent(requested, existing);
		Map<UUID, BandMemberResponseDto> active = activeMembers(requested, existing);
		UUID ownerId = accountUserIds.get(requested.ownerAccountKey());
		BandMemberResponseDto owner = active.get(ownerId);
		if (owner == null || !FOUNDER.equals(owner.role()) || !ACTIVE.equals(owner.status())) {
			throw mismatch(requested.key(), "manifest owner is not the active founder");
		}

		Map<UUID, BandMember> expected = requested.members().stream().collect(Collectors.toMap(
				member -> accountUserIds.get(member.accountKey()), Function.identity()));
		for (BandMemberResponseDto member : active.values()) {
			BandMember planned = expected.get(member.userId());
			if (planned == null) throw mismatch(requested.key(), "band has an unexpected active member");
			assertActiveRole(requested, member, planned);
		}
	}

	private void assertExactRoster(
			Band requested,
			BandResponseDto completed,
			Map<String, UUID> accountUserIds
	) {
		assertBandContent(requested, completed);
		Map<UUID, BandMemberResponseDto> active = activeMembers(requested, completed);
		if (active.size() != requested.members().size()) {
			throw mismatch(requested.key(), "active roster size differs from the manifest");
		}
		for (BandMember planned : requested.members()) {
			UUID userId = accountUserIds.get(planned.accountKey());
			BandMemberResponseDto actual = active.get(userId);
			if (actual == null) throw mismatch(requested.key(), "manifest member is not active");
			assertActiveRole(requested, actual, planned);
			if (planned.role() == BandMemberRole.MANAGER) {
				assertManagerMember(requested, actual, userId);
			} else if (actual.memberTitle() != null && !actual.memberTitle().isBlank()) {
				throw mismatch(requested.key(), "non-manager has an unexpected display title");
			}
		}
	}

	private void assertActiveRole(Band band, BandMemberResponseDto actual, BandMember planned) {
		if (!ACTIVE.equals(actual.status())) {
			throw mismatch(band.key(), "roster contains a non-active member");
		}
		String expectedRole = planned.role() == BandMemberRole.FOUNDER ? FOUNDER : MEMBER;
		if (!expectedRole.equals(actual.role())) {
			throw mismatch(band.key(), "membership role conflicts with the manifest mapping");
		}
	}

	private void assertManagerMember(Band band, BandMemberResponseDto member, UUID userId) {
		if (member == null || !userId.equals(member.userId()) || !ACTIVE.equals(member.status())
				|| !MEMBER.equals(member.role()) || !MANAGER_TITLE.equals(member.memberTitle())) {
			throw mismatch(band.key(), "MANAGER must be an active MEMBER titled Menajer");
		}
	}

	private Map<UUID, BandMemberResponseDto> activeMembers(Band requested, BandResponseDto response) {
		if (response.members() == null) throw mismatch(requested.key(), "band roster is missing");
		LinkedHashMap<UUID, BandMemberResponseDto> result = new LinkedHashMap<>();
		for (BandMemberResponseDto member : response.members()) {
			if (member == null || member.userId() == null || result.putIfAbsent(member.userId(), member) != null) {
				throw mismatch(requested.key(), "band roster contains an invalid duplicate identity");
			}
		}
		return result;
	}

	private Set<UUID> pendingMemberIds(Band requested, UUID ownerId, UUID bandId) {
		LinkedHashSet<UUID> result = new LinkedHashSet<>();
		int pageNumber = 0;
		int totalPages;
		do {
			PageResponse<BandPendingInvitationResponseDto> page = bandService.getPendingInvitations(
					bandId, ownerId, pageNumber, INVITATION_PAGE_SIZE);
			if (page == null || page.content() == null || page.page() != pageNumber
					|| page.totalPages() < 0 || page.totalPages() > 10_001) {
				throw mismatch(requested.key(), "pending invitation page is invalid");
			}
			for (BandPendingInvitationResponseDto invitation : page.content()) {
				if (invitation == null || invitation.userId() == null || !result.add(invitation.userId())) {
					throw mismatch(requested.key(), "pending invitations contain an invalid duplicate identity");
				}
			}
			totalPages = page.totalPages();
			pageNumber++;
		} while (pageNumber < totalPages);
		return Set.copyOf(result);
	}

	private void assertInvitation(Band band, UUID bandId, BandReceivedInvitationResponseDto invitation) {
		if (invitation == null || !bandId.equals(invitation.bandId())
				|| invitation.invitationId() == null || !"PENDING".equals(invitation.status())) {
			throw mismatch(band.key(), "current invitation identity/status is invalid");
		}
	}

	private void assertBandContent(Band requested, BandResponseDto actual) {
		if (actual == null || !requested.name().equals(actual.name())
				|| !Objects.equals(requested.bio(), actual.description())) {
			throw mismatch(requested.key(), "existing band identity/content conflicts with the manifest");
		}
	}

	private Preflight validateInputs(
			SimulationWorldManifest manifest,
			Map<String, UUID> accountUserIds
	) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(accountUserIds, "accountUserIds");
		LinkedHashMap<String, Account> accounts = new LinkedHashMap<>();
		for (Account account : manifest.accounts()) {
			if (account == null || account.key() == null || account.key().isBlank()
					|| accounts.putIfAbsent(account.key(), account) != null) {
				throw new IllegalArgumentException("Manifest contains an invalid or duplicate account key");
			}
		}
		if (!accountUserIds.keySet().equals(accounts.keySet())) {
			throw new IllegalArgumentException("Account id map must exactly match the simulation manifest");
		}
		HashSet<UUID> userIds = new HashSet<>();
		accountUserIds.forEach((key, id) -> {
			if (id == null || !userIds.add(id)) {
				throw new IllegalArgumentException("Account id map contains a null or duplicate user id: " + key);
			}
		});

		LinkedHashSet<String> bandKeys = new LinkedHashSet<>();
		LinkedHashSet<String> normalizedNames = new LinkedHashSet<>();
		for (Band band : manifest.bands()) {
			if (band == null || band.key() == null || band.key().isBlank()
					|| !bandKeys.add(band.key()) || band.name() == null || band.name().isBlank()
					|| !normalizedNames.add(band.name().strip().toLowerCase(java.util.Locale.ROOT))) {
				throw new IllegalArgumentException("Manifest contains an invalid/duplicate band identity");
			}
			LinkedHashSet<String> memberKeys = new LinkedHashSet<>();
			long founderCount = 0;
			for (BandMember member : band.members()) {
				Account account = member == null ? null : accounts.get(member.accountKey());
				if (account == null || !memberKeys.add(member.accountKey())
						|| account.role() != AccountRole.MUSICIAN
						|| account.emailVerification() != EmailVerificationState.VERIFIED) {
					throw new IllegalArgumentException("Band members must be unique verified musicians: " + band.key());
				}
				if (member.role() == BandMemberRole.FOUNDER) founderCount++;
			}
			if (founderCount != 1 || !memberKeys.contains(band.ownerAccountKey())
					|| band.members().stream().noneMatch(member -> member.accountKey().equals(band.ownerAccountKey())
					&& member.role() == BandMemberRole.FOUNDER)) {
				throw new IllegalArgumentException("Band owner must be the sole manifest founder: " + band.key());
			}
		}
		return new Preflight(Set.copyOf(bandKeys));
	}

	private <T> void validateDto(T dto, String bandKey) {
		Set<ConstraintViolation<T>> violations = validator.validate(dto);
		if (!violations.isEmpty()) {
			throw new ConstraintViolationException("Invalid simulation band DTO for " + bandKey, violations);
		}
	}

	private static IllegalStateException mismatch(String bandKey, String detail) {
		return new IllegalStateException("Simulation band mismatch for " + bandKey + ": " + detail);
	}

	private static void succeeded(SimulationRunLedger ledger, String bandKey, String detail) {
		if (ledger != null) ledger.succeeded(PHASE, "materialize", null, bandKey, detail);
	}

	private record Preflight(Set<String> bandKeys) {
	}
}
