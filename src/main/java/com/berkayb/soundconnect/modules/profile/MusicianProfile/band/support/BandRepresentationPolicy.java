package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Defines who may publicly represent a band in cross-domain features.
 *
 * <p>Band membership alone is deliberately insufficient. Until the product has
 * an explicit delegation flow, only an active founder can act on behalf of a
 * band. Keeping that rule here prevents consumers from accidentally treating a
 * manager or a stale membership as ownership.</p>
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BandRepresentationPolicy {

	private final BandMemberRepository bandMemberRepository;

	public List<Band> findRepresentableBands(UUID userId) {
		if (userId == null) return List.of();

		return bandMemberRepository.findByUserIdAndStatusAndBandRole(
					userId,
					BandMemberShipStatus.ACTIVE,
					BandRole.FOUNDER
			)
			.stream()
			.map(BandMember::getBand)
			.toList();
	}

	public Optional<Band> findRepresentableBand(UUID userId, UUID bandId) {
		if (userId == null || bandId == null) return Optional.empty();

		return bandMemberRepository.findByBandIdAndUserIdAndStatusAndBandRole(
					bandId,
					userId,
					BandMemberShipStatus.ACTIVE,
					BandRole.FOUNDER
			)
			.map(BandMember::getBand);
	}

	public boolean canRepresent(UUID userId, UUID bandId) {
		return findRepresentableBand(userId, bandId).isPresent();
	}

	public Band requireRepresentableBand(UUID userId, UUID bandId) {
		return findRepresentableBand(userId, bandId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.FORBIDDEN_ACCESS));
	}
}
