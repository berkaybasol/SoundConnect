package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support;

import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandRole;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.repository.BandMemberRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BandRepresentationPolicyTest {

	@Mock
	private BandMemberRepository bandMemberRepository;

	@Test
	void resolvesOnlyActiveFounderMemberships() {
		UUID userId = UUID.randomUUID();
		Band band = Band.builder().id(UUID.randomUUID()).name("Gece Hatti").build();
		BandMember membership = BandMember.builder().band(band).build();
		when(bandMemberRepository.findByUserIdAndStatusAndBandRole(
				userId,
				BandMemberShipStatus.ACTIVE,
				BandRole.FOUNDER
		)).thenReturn(List.of(membership));

		BandRepresentationPolicy policy = new BandRepresentationPolicy(bandMemberRepository);

		assertThat(policy.findRepresentableBands(userId)).containsExactly(band);
		verify(bandMemberRepository).findByUserIdAndStatusAndBandRole(
				userId,
				BandMemberShipStatus.ACTIVE,
				BandRole.FOUNDER
		);
	}

	@Test
	void requireRepresentationFailsClosedWhenActiveFounderMembershipDoesNotExist() {
		UUID userId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		when(bandMemberRepository.findByBandIdAndUserIdAndStatusAndBandRole(
				bandId,
				userId,
				BandMemberShipStatus.ACTIVE,
				BandRole.FOUNDER
		)).thenReturn(Optional.empty());

		BandRepresentationPolicy policy = new BandRepresentationPolicy(bandMemberRepository);

		assertThatThrownBy(() -> policy.requireRepresentableBand(userId, bandId))
				.isInstanceOf(SoundConnectException.class)
				.satisfies(error -> assertThat(((SoundConnectException) error).getErrorType())
						.isEqualTo(ErrorType.FORBIDDEN_ACCESS));
	}
}
