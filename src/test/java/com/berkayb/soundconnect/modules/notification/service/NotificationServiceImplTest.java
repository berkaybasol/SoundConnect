package com.berkayb.soundconnect.modules.notification.service;

import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.entity.Notification;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.helper.NotificationBadgeCacheHelper;
import com.berkayb.soundconnect.modules.notification.mapper.NotificationMapper;
import com.berkayb.soundconnect.modules.notification.repository.NotificationRepository;
import com.berkayb.soundconnect.modules.notification.repository.NotificationReceiptRepository;
import com.berkayb.soundconnect.modules.notification.websocket.NotificationWebSocketService;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.enums.ListenerVisibilityMode;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceImplTest {
	
	@Mock
	private NotificationRepository notificationRepository;
	
	@Mock
	private NotificationMapper notificationMapper;
	
	@Mock
	private NotificationBadgeCacheHelper badgeCacheHelper;

	@Mock
	private NotificationWebSocketService notificationWebSocketService;

	@Mock
	private GhostListenerIdentityBatchResolver ghostListenerIdentityBatchResolver;
	@Mock private NotificationReceiptRepository receiptRepository;
	
	private NotificationServiceImpl service;
	
	private UUID userId;
	
	@BeforeEach
	void setUp() {
		MockitoAnnotations.openMocks(this);
		service = new NotificationServiceImpl(
				notificationRepository,
				notificationMapper,
				badgeCacheHelper,
				notificationWebSocketService,
				ghostListenerIdentityBatchResolver, receiptRepository);
		userId = UUID.randomUUID();
	}
	
	// ---------- getUserNotifications ----------
	@Test
	void realtimeDeliveryReplacesDelayedActorSnapshotWithCurrentGhostIdentity() {
		UUID follower = UUID.randomUUID();
		NotificationResponseDto stale = new NotificationResponseDto(UUID.randomUUID(), userId,
				NotificationType.SOCIAL_NEW_BAND_FOLLOWER, "Old real name", "message", false, null,
				Map.of("followerId", follower.toString(), "followerUsername", "Old real name",
						"followerAvatarUrl", "https://cdn.test/old-real-face.jpg"));
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of(follower,
				new GhostListenerIdentity(follower, "ghost_alias", null, ListenerVisibilityMode.GHOST)));

		NotificationResponseDto result = service.refreshActorIdentityForDelivery(stale);

		assertThat(result.title()).isEqualTo("ghost_alias bandını takip etmeye başladı");
		assertThat(result.payload()).containsEntry("followerUsername", "ghost_alias")
				.containsEntry("followerVisibilityMode", "GHOST").doesNotContainKey("followerAvatarUrl");
		assertThat(stale.payload()).containsEntry("followerUsername", "Old real name");
	}

	@Test
	void realtimeDeliveryFailsClosedIfCurrentIdentityCannotBeRead() throws Exception {
		UUID sender = UUID.randomUUID();
		NotificationResponseDto stale = new NotificationResponseDto(UUID.randomUUID(), userId,
				NotificationType.DM_NEW_MESSAGE, "Old real name", "message", false, null,
				Map.of("senderId", sender.toString(), "senderUsername", "Old real name",
						"senderAvatarUrl", "https://cdn.test/old-real-face.jpg"));
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection())).thenThrow(new IllegalStateException("Unavailable"));

		NotificationResponseDto result = service.refreshActorIdentityForDelivery(stale);

		assertThat(result.title()).isEqualTo("Yeni mesaj");
		assertThat(result.payload()).containsEntry("senderId", sender.toString())
				.doesNotContainKeys("senderUsername", "senderAvatarUrl");
		assertThat(NotificationServiceImpl.class.getMethod("refreshActorIdentityForDelivery", NotificationResponseDto.class)
				.getAnnotation(Transactional.class).propagation())
				.isEqualTo(org.springframework.transaction.annotation.Propagation.REQUIRED);
	}

	@Test
	@DisplayName("getUserNotifications: repository page -> mapper.toDto ile dönmeli")
	void getUserNotifications_ok() {
		Notification n = Notification.builder()
		                             .recipientId(userId)
		                             .type(NotificationType.MEDIA_UPLOAD_RECEVIED)
		                             .read(false)
		                             .build();
		Page<Notification> page = new PageImpl<>(List.of(n));
		when(notificationRepository.findByRecipientId(eq(userId), any(Pageable.class)))
				.thenReturn(page);
		
		NotificationResponseDto dto = new NotificationResponseDto(
				UUID.randomUUID(), userId, NotificationType.MEDIA_UPLOAD_RECEVIED,
				"t", "m", false, null, Map.of()
		);
		when(notificationMapper.toDto(n)).thenReturn(dto);
		
		Page<NotificationResponseDto> result = service.getUserNotifications(userId, 0, 20);
		
		assertThat(result.getContent()).containsExactly(dto);
		ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
		verify(notificationRepository).findByRecipientId(
				eq(userId), pageableCaptor.capture());
		assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
		assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
		assertThat(pageableCaptor.getValue().getSort().toList())
				.extracting(Sort.Order::getProperty, Sort.Order::getDirection)
				.containsExactly(
						tuple("occurredAt", Sort.Direction.DESC),
						tuple("id", Sort.Direction.DESC)
				);
		verify(notificationMapper).toDto(n);
	}

	@Test
	@DisplayName("getUserNotifications: page ve size sinirlarini repository oncesi uygular")
	void getUserNotifications_rejectsInvalidPaginationBeforeRepositoryAccess() {
		assertThatThrownBy(() -> service.getUserNotifications(userId, 1001, 20))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));
		assertThatThrownBy(() -> service.getUserNotifications(userId, 0, 101))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.VALIDATION_ERROR));

		verifyNoInteractions(notificationRepository, notificationMapper);
	}

	@Test
	@DisplayName("getUserNotifications: current ghost identity replaces every persisted DM identity snapshot")
	void getUserNotifications_rehydratesCurrentGhostIdentityFromStableSenderId() {
		UUID senderId = UUID.randomUUID();
		UUID notificationId = UUID.randomUUID();
		Notification entity = Notification.builder()
				.recipientId(userId)
				.type(NotificationType.DM_NEW_MESSAGE)
				.read(false)
				.build();
		entity.setId(notificationId);
		Map<String, Object> stalePayload = new LinkedHashMap<>();
		stalePayload.put("module", "DM");
		stalePayload.put("senderId", senderId.toString());
		stalePayload.put("conversationId", UUID.randomUUID().toString());
		stalePayload.put("senderUsername", "Stale Stage Name");
		stalePayload.put("senderAvatarUrl", "https://cdn.example/stale.jpg");
		NotificationResponseDto stale = new NotificationResponseDto(
				notificationId,
				userId,
				NotificationType.DM_NEW_MESSAGE,
				"Stale Stage Name size bir mesaj gönderdi",
				"hello",
				false,
				null,
				stalePayload
		);
		when(notificationRepository.findByRecipientId(eq(userId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(entity)));
		when(notificationMapper.toDto(entity)).thenReturn(stale);
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of(
				senderId,
				new GhostListenerIdentity(
						senderId,
						"canonical_listener",
						"https://cdn.example/current-listener.jpg",
						ListenerVisibilityMode.GHOST
				)
		));

		NotificationResponseDto result = service.getUserNotifications(userId, 0, 20)
				.getContent().getFirst();

		assertThat(result.title()).isEqualTo("canonical_listener size bir mesaj gönderdi");
		assertThat(result.payload())
				.containsEntry("senderId", senderId.toString())
				.containsEntry("senderUsername", "canonical_listener")
				.containsEntry("senderAvatarUrl", "https://cdn.example/current-listener.jpg")
				.containsEntry("senderVisibilityMode", "GHOST")
				.doesNotContainValue("Stale Stage Name")
				.doesNotContainValue("https://cdn.example/stale.jpg");
		verify(ghostListenerIdentityBatchResolver).resolve(argThat(ids ->
				ids.size() == 1 && ids.contains(senderId)));
	}

	@Test
	@DisplayName("getRecentNotifications: ghost without an avatar removes a persisted stale avatar")
	void getRecentNotifications_ghostWithoutAvatarRemovesStaleAvatar() {
		UUID senderId = UUID.randomUUID();
		Notification entity = Notification.builder()
				.recipientId(userId)
				.type(NotificationType.DM_NEW_MESSAGE)
				.read(false)
				.build();
		NotificationResponseDto stale = new NotificationResponseDto(
				UUID.randomUUID(), userId, NotificationType.DM_NEW_MESSAGE,
				"Old Name size bir mesaj gönderdi", "hello", false, null,
				Map.of(
						"senderId", senderId.toString(),
						"senderUsername", "Old Name",
						"senderAvatarUrl", "https://cdn.example/old.jpg"
				)
		);
		when(notificationRepository.findTop10ByRecipientIdOrderByOccurredAtDescIdDesc(userId))
				.thenReturn(List.of(entity));
		when(notificationMapper.toDtoList(List.of(entity))).thenReturn(List.of(stale));
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of(
				senderId,
				new GhostListenerIdentity(senderId, "ghost_user", null, ListenerVisibilityMode.GHOST)
		));

		NotificationResponseDto result = service.getRecentNotifications(userId).getFirst();

		assertThat(result.payload())
				.containsEntry("senderUsername", "ghost_user")
				.containsEntry("senderVisibilityMode", "GHOST")
				.doesNotContainKey("senderAvatarUrl");
	}

	@Test
	@DisplayName("getUserNotifications: identity lookup failure strips snapshots but preserves stable routing keys")
	void getUserNotifications_identityResolverFailureFailsClosed() {
		UUID senderId = UUID.randomUUID();
		String conversationId = UUID.randomUUID().toString();
		Notification entity = Notification.builder()
				.recipientId(userId)
				.type(NotificationType.DM_NEW_MESSAGE)
				.read(false)
				.build();
		NotificationResponseDto stale = new NotificationResponseDto(
				UUID.randomUUID(), userId, NotificationType.DM_NEW_MESSAGE,
				"Private Display Name size bir mesaj gönderdi", "hello", false, null,
				Map.of(
						"senderId", senderId.toString(),
						"conversationId", conversationId,
						"senderUsername", "Private Display Name",
						"senderAvatarUrl", "https://cdn.example/private.jpg",
						"senderVisibilityMode", "STANDARD"
				)
		);
		when(notificationRepository.findByRecipientId(eq(userId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(entity)));
		when(notificationMapper.toDto(entity)).thenReturn(stale);
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection()))
				.thenThrow(new IllegalStateException("identity repository unavailable"));

		NotificationResponseDto result = service.getUserNotifications(userId, 0, 20)
				.getContent().getFirst();

		assertThat(result.title()).isEqualTo("Yeni mesaj");
		assertThat(result.payload())
				.containsEntry("senderId", senderId.toString())
				.containsEntry("conversationId", conversationId)
				.doesNotContainKeys("senderUsername", "senderAvatarUrl", "senderVisibilityMode");
	}

	@Test
	@DisplayName("getUserNotifications: standard DM payload remains byte-shape compatible and marker-free")
	void getUserNotifications_standardIdentityKeepsExistingPayloadShape() {
		UUID senderId = UUID.randomUUID();
		Notification entity = Notification.builder()
				.recipientId(userId)
				.type(NotificationType.DM_NEW_MESSAGE)
				.read(false)
				.build();
		NotificationResponseDto standard = new NotificationResponseDto(
				UUID.randomUUID(), userId, NotificationType.DM_NEW_MESSAGE,
				"artist size bir mesaj gönderdi", "hello", false, null,
				Map.of(
						"senderId", senderId.toString(),
						"senderUsername", "artist",
						"senderAvatarUrl", "https://cdn.example/artist.jpg"
				)
		);
		when(notificationRepository.findByRecipientId(eq(userId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(entity)));
		when(notificationMapper.toDto(entity)).thenReturn(standard);
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of());

		NotificationResponseDto result = service.getUserNotifications(userId, 0, 20)
				.getContent().getFirst();

		assertThat(result).isSameAs(standard);
		assertThat(result.payload()).doesNotContainKey("senderVisibilityMode");
	}

	@Test
	@DisplayName("getUserNotifications: both follower notification types share one ghost identity batch")
	void getUserNotifications_rehydratesGhostFollowerTypesInOneBatch() {
		UUID followerId = UUID.randomUUID();
		UUID bandFollowerId = UUID.randomUUID();
		Notification directEntity = Notification.builder()
				.recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_FOLLOWER)
				.read(false)
				.build();
		directEntity.setId(UUID.randomUUID());
		Notification bandEntity = Notification.builder()
				.recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_BAND_FOLLOWER)
				.read(false)
				.build();
		bandEntity.setId(UUID.randomUUID());
		NotificationResponseDto direct = new NotificationResponseDto(
				UUID.randomUUID(), userId, NotificationType.SOCIAL_NEW_FOLLOWER,
				"Old Venue Name seni takip etmeye başladı", "Yeni bir takipçin var.", false, null,
				Map.of(
						"followerId", followerId.toString(),
						"followerUsername", "Old Venue Name",
						"followerAvatarUrl", "https://cdn.example/old-venue.jpg"
				)
		);
		String bandId = UUID.randomUUID().toString();
		NotificationResponseDto band = new NotificationResponseDto(
				UUID.randomUUID(), userId, NotificationType.SOCIAL_NEW_BAND_FOLLOWER,
				"Old Artist bandını takip etmeye başladı", "Band yeni bir takipçi kazandı.", false, null,
				Map.of(
						"followerId", bandFollowerId.toString(),
						"followerUsername", "Old Artist",
						"followerAvatarUrl", "https://cdn.example/old-artist.jpg",
						"bandId", bandId
				)
		);
		when(notificationRepository.findByRecipientId(eq(userId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(directEntity, bandEntity)));
		when(notificationMapper.toDto(directEntity)).thenReturn(direct);
		when(notificationMapper.toDto(bandEntity)).thenReturn(band);
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of(
				followerId,
				new GhostListenerIdentity(
						followerId,
						"listener_one",
						"https://cdn.example/listener-one.jpg",
						ListenerVisibilityMode.GHOST
				),
				bandFollowerId,
				new GhostListenerIdentity(
						bandFollowerId,
						"listener_two",
						null,
						ListenerVisibilityMode.GHOST
				)
		));

		List<NotificationResponseDto> result = service.getUserNotifications(userId, 0, 20).getContent();

		assertThat(result.get(0).title()).isEqualTo("listener_one seni takip etmeye başladı");
		assertThat(result.get(0).payload())
				.containsEntry("followerId", followerId.toString())
				.containsEntry("followerUsername", "listener_one")
				.containsEntry("followerAvatarUrl", "https://cdn.example/listener-one.jpg")
				.containsEntry("followerVisibilityMode", "GHOST")
				.doesNotContainValue("Old Venue Name")
				.doesNotContainValue("https://cdn.example/old-venue.jpg");
		assertThat(result.get(1).title()).isEqualTo("listener_two bandını takip etmeye başladı");
		assertThat(result.get(1).payload())
				.containsEntry("followerId", bandFollowerId.toString())
				.containsEntry("followerUsername", "listener_two")
				.containsEntry("followerVisibilityMode", "GHOST")
				.containsEntry("bandId", bandId)
				.doesNotContainKey("followerAvatarUrl");
		verify(ghostListenerIdentityBatchResolver).resolve(argThat(ids ->
				ids.size() == 2 && ids.containsAll(List.of(followerId, bandFollowerId))));
	}

	@Test
	@DisplayName("getUserNotifications: follower resolver failure strips identity but preserves routing")
	void getUserNotifications_followerResolverFailureFailsClosed() {
		UUID followerId = UUID.randomUUID();
		String bandId = UUID.randomUUID().toString();
		Notification entity = Notification.builder()
				.recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_BAND_FOLLOWER)
				.read(false)
				.build();
		NotificationResponseDto stale = new NotificationResponseDto(
				UUID.randomUUID(), userId, NotificationType.SOCIAL_NEW_BAND_FOLLOWER,
				"Identifying Name bandını takip etmeye başladı", "message", false, null,
				Map.of(
						"followerId", followerId.toString(),
						"followerUsername", "Identifying Name",
						"followerAvatarUrl", "https://cdn.example/identifying.jpg",
						"followerVisibilityMode", "STANDARD",
						"bandId", bandId
				)
		);
		when(notificationRepository.findByRecipientId(eq(userId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(entity)));
		when(notificationMapper.toDto(entity)).thenReturn(stale);
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection()))
				.thenThrow(new IllegalStateException("identity repository unavailable"));

		NotificationResponseDto result = service.getUserNotifications(userId, 0, 20)
				.getContent().getFirst();

		assertThat(result.title()).isEqualTo("Yeni band takipçisi");
		assertThat(result.payload())
				.containsEntry("followerId", followerId.toString())
				.containsEntry("bandId", bandId)
				.doesNotContainKeys("followerUsername", "followerAvatarUrl", "followerVisibilityMode");
	}

	@Test
	@DisplayName("getUserNotifications: invalid followerId is sanitized without calling the resolver")
	void getUserNotifications_invalidFollowerIdFailsClosed() {
		Notification entity = Notification.builder()
				.recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_FOLLOWER)
				.read(false)
				.build();
		NotificationResponseDto stale = new NotificationResponseDto(
				UUID.randomUUID(), userId, NotificationType.SOCIAL_NEW_FOLLOWER,
				"Identifying Name seni takip etmeye başladı", "message", false, null,
				Map.of(
						"followerId", "not-a-uuid",
						"followerUsername", "Identifying Name",
						"followerAvatarUrl", "https://cdn.example/identifying.jpg"
				)
		);
		when(notificationRepository.findByRecipientId(eq(userId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(entity)));
		when(notificationMapper.toDto(entity)).thenReturn(stale);

		NotificationResponseDto result = service.getUserNotifications(userId, 0, 20)
				.getContent().getFirst();

		assertThat(result.title()).isEqualTo("Yeni takipçi");
		assertThat(result.payload())
				.containsEntry("followerId", "not-a-uuid")
				.doesNotContainKeys("followerUsername", "followerAvatarUrl", "followerVisibilityMode");
		verifyNoInteractions(ghostListenerIdentityBatchResolver);
	}

	@Test
	@DisplayName("getUserNotifications: current standard follower keeps snapshot and loses stale ghost marker")
	void getUserNotifications_standardFollowerKeepsSnapshotWithoutGhostMarker() {
		UUID followerId = UUID.randomUUID();
		Notification entity = Notification.builder()
				.recipientId(userId)
				.type(NotificationType.SOCIAL_NEW_FOLLOWER)
				.read(false)
				.build();
		NotificationResponseDto staleMarker = new NotificationResponseDto(
				UUID.randomUUID(), userId, NotificationType.SOCIAL_NEW_FOLLOWER,
				"artist seni takip etmeye başladı", "message", false, null,
				Map.of(
						"followerId", followerId.toString(),
						"followerUsername", "artist",
						"followerAvatarUrl", "https://cdn.example/artist.jpg",
						"followerVisibilityMode", "GHOST"
				)
		);
		when(notificationRepository.findByRecipientId(eq(userId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(entity)));
		when(notificationMapper.toDto(entity)).thenReturn(staleMarker);
		when(ghostListenerIdentityBatchResolver.resolve(anyCollection())).thenReturn(Map.of());

		NotificationResponseDto result = service.getUserNotifications(userId, 0, 20)
				.getContent().getFirst();

		assertThat(result.title()).isEqualTo(staleMarker.title());
		assertThat(result.payload())
				.containsEntry("followerUsername", "artist")
				.containsEntry("followerAvatarUrl", "https://cdn.example/artist.jpg")
				.doesNotContainKey("followerVisibilityMode");
	}

	@Test
	@DisplayName("notification identity reads keep resolver locks in a write-capable outer transaction")
	void notificationIdentityReadMethodsAreWriteCapableTransactions() throws Exception {
		Transactional all = NotificationServiceImpl.class
				.getMethod("getUserNotifications", UUID.class, int.class, int.class)
				.getAnnotation(Transactional.class);
		Transactional filtered = NotificationServiceImpl.class
				.getMethod("getUserNotificationsByTypes", UUID.class, Collection.class, int.class, int.class)
				.getAnnotation(Transactional.class);
		Transactional recent = NotificationServiceImpl.class
				.getMethod("getRecentNotifications", UUID.class)
				.getAnnotation(Transactional.class);

		assertThat(List.of(all, filtered, recent))
				.allSatisfy(transaction -> {
					assertThat(transaction).isNotNull();
					assertThat(transaction.readOnly()).isFalse();
				});
	}
	
	// ---------- getUserNotificationsByTypes ----------
	@Test
	@DisplayName("getUserNotificationsByTypes: filtreli çağrı ve mapping (Answer ile argümana göre DTO seç)")
	void getUserNotificationsByTypes_ok() {
		Set<NotificationType> types = EnumSet.of(NotificationType.MEDIA_TRANSCODE_READY, NotificationType.AUTH_EMAIL_VERIFIED);
		
		Notification n1 = Notification.builder()
		                              .recipientId(userId)
		                              .type(NotificationType.MEDIA_TRANSCODE_READY)
		                              .read(false)
		                              .build();
		
		Notification n2 = Notification.builder()
		                              .recipientId(userId)
		                              .type(NotificationType.AUTH_EMAIL_VERIFIED)
		                              .read(true)
		                              .build();
		
		Page<Notification> page = new PageImpl<>(List.of(n1, n2));
		
		when(notificationRepository.findByRecipientIdAndTypeIn(eq(userId), anyCollection(), any(Pageable.class)))
				.thenReturn(page);
		
		NotificationResponseDto d1 = new NotificationResponseDto(
				UUID.randomUUID(), userId, n1.getType(), "t1","m1", false, null, null);
		NotificationResponseDto d2 = new NotificationResponseDto(
				UUID.randomUUID(), userId, n2.getType(), "t2","m2", true, null, null);
		
		// Kritik kısım: hangi entity geldiyse ona uygun DTO’yu döndür.
		when(notificationMapper.toDto(any(Notification.class))).thenAnswer(inv -> {
			Notification arg = inv.getArgument(0, Notification.class);
			return arg.getType() == NotificationType.MEDIA_TRANSCODE_READY ? d1 : d2;
		});
		
		Page<NotificationResponseDto> result = service.getUserNotificationsByTypes(
				userId, types, 0, 10);
		
		assertThat(result.getContent()).containsExactly(d1, d2);
		verify(notificationRepository).findByRecipientIdAndTypeIn(eq(userId), eq(types), any(Pageable.class));
		verify(notificationMapper, times(2)).toDto(any(Notification.class));
	}
	
	// ---------- getRecentNotifications ----------
	@Test
	@DisplayName("getRecentNotifications: repo top10 + mapper.toDtoList")
	void getRecentNotifications_ok() {
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.MEDIA_TRANSCODE_FAILED).read(false).build();
		when(notificationRepository.findTop10ByRecipientIdOrderByOccurredAtDescIdDesc(userId))
				.thenReturn(List.of(n));
		
		NotificationResponseDto dto = new NotificationResponseDto(UUID.randomUUID(), userId, n.getType(), "t","m", false, null, null);
		when(notificationMapper.toDtoList(List.of(n))).thenReturn(List.of(dto));
		
		List<NotificationResponseDto> list = service.getRecentNotifications(userId);
		
		assertThat(list).containsExactly(dto);
		verify(notificationRepository).findTop10ByRecipientIdOrderByOccurredAtDescIdDesc(userId);
		verify(notificationMapper).toDtoList(List.of(n));
	}
	
	// ---------- getUnreadCount ----------
	@Test
	@DisplayName("getUnreadCount: stale Redis projection yerine DB source of truth kullanılır")
	void getUnreadCount_staleCacheCannotOverrideDatabase() {
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(5L);
		
		long c = service.getUnreadCount(userId);
		
		assertThat(c).isEqualTo(5L);
		verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper).setUnreadWithTtl(userId, 5L);
		verify(badgeCacheHelper, never()).getCacheUnread(any());
	}
	
	@Test
	@DisplayName("getUnreadCount: DB sayımı best-effort Redis projection'ı tazeler")
	void getUnreadCount_refreshesProjectionFromDatabase() {
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(7L);
		
		long c = service.getUnreadCount(userId);
		
		assertThat(c).isEqualTo(7L);
		InOrder in = inOrder(notificationRepository, badgeCacheHelper);
		in.verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		in.verify(badgeCacheHelper).setUnreadWithTtl(userId, 7L);
	}
	
	// ---------- markAsRead ----------
	@Test
	@DisplayName("markAsRead: bildirim bulunamazsa NOT_FOUND hatası fırlatır")
	void markAsRead_notFound() {
		UUID notifId = UUID.randomUUID();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.markAsRead(userId, notifId))
				.isInstanceOf(SoundConnectException.class);
		
		verify(notificationRepository, never()).markAsRead(any(), any());
	}
	
	@Test
	@DisplayName("markAsRead: zaten read ise idempotent success ve fresh badge projeksiyonu")
	void markAsRead_alreadyReadIsIdempotent() {
		UUID notifId = UUID.randomUUID();
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.SOCIAL_NEW_FOLLOWER).read(true).build();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.of(n));
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(6L);
		
		assertThatCode(() -> service.markAsRead(userId, notifId)).doesNotThrowAnyException();
		
		verify(notificationRepository, never()).markAsRead(any(), any());
		verify(badgeCacheHelper).setUnread(userId, 6L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 6L);
	}
	
	@Test
	@DisplayName("markAsRead: başarılı update sonrası fresh unread sayılır ve cache güvenli azaltılır")
	void markAsRead_success() {
		UUID notifId = UUID.randomUUID();
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.AUTH_EMAIL_VERIFIED).read(false).build();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.of(n));
		when(notificationRepository.markAsRead(notifId, userId)).thenReturn(1);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(3L);
		
		service.markAsRead(userId, notifId);
		
		verify(notificationRepository).markAsRead(notifId, userId);
		verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper).setUnread(userId, 3L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 3L);
	}
	
	// ---------- markAllAsRead ----------
	@Test
	@DisplayName("markAllAsRead: update=0 olsa da cihazlari fresh badge ile uzlastirir")
	void markAllAsRead_zero() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(0);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(0L);
		
		int updated = service.markAllAsRead(userId);
		
		assertThat(updated).isEqualTo(0);
		verify(badgeCacheHelper).setUnread(userId, 0L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 0L);
	}
	
	@Test
	@DisplayName("markAllAsRead: update>0 ise cache unread=0 yapılır")
	void markAllAsRead_some() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(5);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(0L);
		
		int updated = service.markAllAsRead(userId);
		
		assertThat(updated).isEqualTo(5);
		verify(badgeCacheHelper).setUnread(userId, 0L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 0L);
	}

	@Test
	@DisplayName("markDmConversationAsRead: cache ve WS badge projection committen sonra bir kez çalışır")
	void markDmConversationAsRead_projectsBadgeOnceAfterCommit() {
		UUID conversationId = UUID.randomUUID();
		when(notificationRepository.markUnreadDmNotificationsAsReadByConversation(
				userId, conversationId.toString())).thenReturn(2);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(4L);
		TransactionSynchronizationManager.initSynchronization();
		try {
			int updated = service.markDmConversationAsRead(userId, conversationId);

			assertThat(updated).isEqualTo(2);
			verify(notificationRepository, never()).countByRecipientIdAndReadIsFalse(any());
			verify(badgeCacheHelper, never()).setUnread(any(), anyLong());
			verifyNoInteractions(notificationWebSocketService);
			List<TransactionSynchronization> synchronizations =
					TransactionSynchronizationManager.getSynchronizations();
			assertThat(synchronizations).hasSize(1);
			synchronizations.getFirst().afterCommit();
			verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
			verify(badgeCacheHelper).setUnread(userId, 4L);
			verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 4L);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("notification mutation rollback olursa cache veya WS phantom badge üretmez")
	void mutationRollbackDoesNotProjectBadge() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(3);
		TransactionSynchronizationManager.initSynchronization();
		try {
			assertThat(service.markAllAsRead(userId)).isEqualTo(3);
			List<TransactionSynchronization> synchronizations =
					TransactionSynchronizationManager.getSynchronizations();
			assertThat(synchronizations).hasSize(1);
			synchronizations.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

			verify(notificationRepository, never()).countByRecipientIdAndReadIsFalse(any());
			verifyNoInteractions(badgeCacheHelper, notificationWebSocketService);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	@DisplayName("notification mutation: Redis projection failure does not suppress committed WS badge")
	void mutationCacheFailureStillProjectsDatabaseBadge() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(2);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(1L);
		doThrow(new IllegalStateException("redis down"))
				.when(badgeCacheHelper).setUnread(userId, 1L);

		assertThat(service.markAllAsRead(userId)).isEqualTo(2);

		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 1L);
	}

	@Test
	@DisplayName("notification mutation: committed DB recount failure does not publish a fabricated badge")
	void mutationRecountFailureDoesNotProjectBadge() {
		when(notificationRepository.markAllAsRead(userId)).thenReturn(2);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId))
				.thenThrow(new IllegalStateException("db unavailable"));

		assertThat(service.markAllAsRead(userId)).isEqualTo(2);

		verifyNoInteractions(badgeCacheHelper, notificationWebSocketService);
	}
	
	// ---------- deleteById ----------
	@Test
	@DisplayName("deleteById: bildirim yoksa NOT_FOUND hatası")
	void deleteById_notFound() {
		UUID notifId = UUID.randomUUID();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.empty());
		
		assertThatThrownBy(() -> service.deleteById(userId, notifId))
				.isInstanceOf(SoundConnectException.class);
	}
	
	@Test
	@DisplayName("deleteById: unread bildirimi silerse fresh unread sayılır ve cache güvenli azaltılır")
	void deleteById_unread() {
		UUID notifId = UUID.randomUUID();
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.MEDIA_TRANSCODE_READY).read(false).build();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.of(n));
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(4L);
		
		boolean ok = service.deleteById(userId, notifId);
		
		assertThat(ok).isTrue();
		verify(notificationRepository).delete(n);
		verify(notificationRepository).countByRecipientIdAndReadIsFalse(userId);
		verify(badgeCacheHelper).setUnread(userId, 4L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 4L);
	}
	
	@Test
	@DisplayName("deleteById: read bildirim silinse de diğer cihazlara fresh badge yayınlanır")
	void deleteById_alreadyReadStillProjectsBadge() {
		UUID notifId = UUID.randomUUID();
		Notification n = Notification.builder().recipientId(userId).type(NotificationType.MEDIA_TRANSCODE_READY).read(true).build();
		when(notificationRepository.findByIdAndRecipientId(notifId, userId)).thenReturn(Optional.of(n));
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(2L);
		
		boolean ok = service.deleteById(userId, notifId);
		
		assertThat(ok).isTrue();
		verify(notificationRepository).delete(n);
		verify(badgeCacheHelper).setUnread(userId, 2L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 2L);
	}

	@Test
	@DisplayName("clearAll: committed fresh badge cache ve WS'e yansıtılır")
	void clearAll_projectsCommittedBadge() {
		when(notificationRepository.deleteByRecipientId(userId)).thenReturn(4);
		when(notificationRepository.countByRecipientIdAndReadIsFalse(userId)).thenReturn(0L);

		assertThat(service.clearAll(userId)).isEqualTo(4);

		verify(badgeCacheHelper).setUnread(userId, 0L);
		verify(notificationWebSocketService).sendUnreadBadgeToUser(userId, 0L);
	}
}
