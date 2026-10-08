package com.berkayb.soundconnect.modules.follow.band.service;

import com.berkayb.soundconnect.modules.follow.band.dto.response.BandFollowResponseDto;
import com.berkayb.soundconnect.modules.follow.band.entity.BandFollow;
import com.berkayb.soundconnect.modules.follow.outbox.FollowNotificationOutboxService;
import com.berkayb.soundconnect.modules.follow.band.mapper.BandFollowMapper;
import com.berkayb.soundconnect.modules.follow.band.repository.BandFollowRepository;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.Band;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.entity.BandMember;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.enums.BandMemberShipStatus;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.support.BandEntityFinder;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BandFollowServiceGhostIdentityTest {

	@Mock BandFollowRepository bandFollowRepository;
	@Mock UserEntityFinder userEntityFinder;
	@Mock BandEntityFinder bandEntityFinder;
	@Mock MediaAssetService mediaAssetService;
	@Mock GhostListenerIdentityBatchResolver ghostIdentityBatchResolver;
	@Mock FollowNotificationOutboxService notificationOutbox;

	private BandFollowServiceImpl service;

	@BeforeEach
	void setUp() {
		BandFollowMapper mapper = Mappers.getMapper(BandFollowMapper.class);
		service = new BandFollowServiceImpl(
				bandFollowRepository,
				userEntityFinder,
				bandEntityFinder,
				mapper,
				mediaAssetService,
				ghostIdentityBatchResolver,
				notificationOutbox
		);
	}

	@Test
	void followedBandListResolvesRepeatedFollowerOnceAndAppliesGhostIdentityToEveryRow() {
		UUID followerId = UUID.randomUUID();
		User follower = User.builder()
				.id(followerId)
				.username("alternate-name")
				.profilePicture("alternate-avatar.png")
				.build();
		Band firstBand = Band.builder().id(UUID.randomUUID()).name("First Band").build();
		Band secondBand = Band.builder().id(UUID.randomUUID()).name("Second Band").build();
		List<BandFollow> follows = List.of(
				follow(follower, firstBand),
				follow(follower, secondBand)
		);
		GhostListenerIdentity identity = new GhostListenerIdentity(
				followerId,
				"listener-handle",
				"listener-avatar.png",
				ListenerVisibilityMode.GHOST
		);
		when(userEntityFinder.getUser(followerId)).thenReturn(follower);
		when(bandFollowRepository.findAllByFollower(follower)).thenReturn(follows);
		when(ghostIdentityBatchResolver.resolve(org.mockito.ArgumentMatchers.anyCollection()))
				.thenReturn(Map.of(followerId, identity));

		List<BandFollowResponseDto> result = service.getMyFollowedBands(followerId);

		assertThat(result).hasSize(2);
		assertThat(result)
				.allSatisfy(dto -> {
					assertThat(dto.followerUsername()).isEqualTo("listener-handle");
					assertThat(dto.followerProfilePicture()).isEqualTo("listener-avatar.png");
					assertThat(dto.followerVisibilityMode()).isEqualTo(ListenerVisibilityMode.GHOST);
				});
		ArgumentCaptor<Collection<UUID>> idsCaptor = collectionCaptor();
		verify(ghostIdentityBatchResolver).resolve(idsCaptor.capture());
		assertThat(idsCaptor.getValue()).containsExactly(followerId);
	}

	@Test
	void followedBandListUsesWriteCapableTransactionForVisibilityReadLock() throws Exception {
		Transactional transaction = BandFollowServiceImpl.class
				.getMethod("getMyFollowedBands", UUID.class)
				.getAnnotation(Transactional.class);

		assertThat(transaction).isNotNull();
		assertThat(transaction.readOnly()).isFalse();
	}

	@Test
	void followBandQueuesOnlyStableIdsForActiveMemberRecipients() {
		UUID followerId = UUID.randomUUID();
		UUID bandId = UUID.randomUUID();
		UUID recipientId = UUID.randomUUID();
		User follower = User.builder().id(followerId).username("follower").build();
		User recipient = User.builder().id(recipientId).username("member").build();
		Band band = Band.builder().id(bandId).name("Band").build();
		BandMember member = BandMember.builder()
				.id(UUID.randomUUID())
				.band(band)
				.user(recipient)
				.status(BandMemberShipStatus.ACTIVE)
				.build();
		band.setMembers(new java.util.HashSet<>(Arrays.asList(member, null,
                BandMember.builder().id(UUID.randomUUID()).user(recipient).status(BandMemberShipStatus.ACTIVE).build(),
                BandMember.builder().id(UUID.randomUUID()).user(follower).status(BandMemberShipStatus.ACTIVE).build(),
                BandMember.builder().id(UUID.randomUUID()).user(User.builder().id(UUID.randomUUID()).build()).status(BandMemberShipStatus.LEFT).build(),
                BandMember.builder().id(UUID.randomUUID()).status(BandMemberShipStatus.ACTIVE).build())));
		when(userEntityFinder.getUser(followerId)).thenReturn(follower);
		when(bandEntityFinder.getBand(bandId)).thenReturn(band);
		when(bandEntityFinder.getBandMember(bandId, followerId))
				.thenThrow(new SoundConnectException(ErrorType.BAND_MEMBER_NOT_FOUND));
		when(bandFollowRepository.existsByFollowerAndBand(follower, band)).thenReturn(false);
		when(bandFollowRepository.save(org.mockito.ArgumentMatchers.any(BandFollow.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
        service.followBand(followerId, bandId);
        verify(notificationOutbox).enqueue(org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(followerId), org.mockito.ArgumentMatchers.eq(recipientId),
                org.mockito.ArgumentMatchers.eq(bandId), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoMoreInteractions(notificationOutbox);
    }

	private BandFollow follow(User follower, Band band) {
		return BandFollow.builder()
				.id(UUID.randomUUID())
				.follower(follower)
				.band(band)
				.followedAt(LocalDateTime.now())
				.build();
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private ArgumentCaptor<Collection<UUID>> collectionCaptor() {
		return (ArgumentCaptor) ArgumentCaptor.forClass(Collection.class);
	}
}
