package com.berkayb.soundconnect.modules.message.dm.service;


import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPreviewResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.model.DmParticipantPair;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentity;
import com.berkayb.soundconnect.modules.profile.shared.identity.GhostListenerIdentityBatchResolver;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.repository.MusicianProfileRepository;
import com.berkayb.soundconnect.modules.profile.OrganizerProfile.repository.OrganizerProfileRepository;
import com.berkayb.soundconnect.modules.profile.ProducerProfile.repository.ProducerProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.VenueProfile.repository.VenueProfileRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DMConversationServiceImpl implements DMConversationService {
	private static final int CONCURRENT_CREATE_LOOKUP_ATTEMPTS = 3;

	private final DMConversationRepository conversationRepository;
	private final DMMessageRepository messageRepository;
	private final UserRepository userRepository;
	private final MusicianProfileRepository musicianProfileRepository;
	private final VenueProfileRepository venueProfileRepository;
	private final OrganizerProfileRepository organizerProfileRepository;
	private final ListenerProfileRepository listenerProfileRepository;
	private final ProducerProfileRepository producerProfileRepository;
	private final StudioProfileRepository studioProfileRepository;
	private final VenueRepository venueRepository;
	private final MediaAssetService mediaAssetService;
	private final GhostListenerIdentityBatchResolver ghostListenerIdentityBatchResolver;
	private final DmConversationCreationService conversationCreationService;


	// kullanicinin dahil oldugu tum konusmalari ozet halinde getirir.
	// her konusma icin son mesaj ve karsi taraf bilgisi profile'dan alinir
	@Override
	@Transactional
	public List<DMConversationPreviewResponseDto> getAllConversationsForUser(UUID userId) {
	// kullanicinin dahil oldugu tum conversationlari bul
		List<DMConversation> conversations = conversationRepository.findByUserAIdOrUserBId(userId, userId);
		Set<UUID> otherUserIds = conversations.stream()
				.map(conversation -> otherUserId(conversation, userId))
				.filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<UUID, GhostListenerIdentity> ghostIdentities =
				ghostListenerIdentityBatchResolver.resolve(otherUserIds);
		if (ghostIdentities == null) ghostIdentities = Map.of();
		Map<UUID, GhostListenerIdentity> resolvedGhostIdentities = ghostIdentities;
		Set<UUID> erasedUserIds = otherUserIds.isEmpty() ? Set.of() : userRepository.findErasedIds(otherUserIds);

		// son mesaji ve karsi tarafi profile lookup ile bul
		List<DMConversationPreviewResponseDto> result = conversations.stream()
				.map(conversation -> {
					// son mesaji bul
					Optional<DMMessage> lastMessageOpt = messageRepository.findTopByConversationIdOrderByCreatedAtDesc(conversation.getId());

					// karsi tarafi bul (userB veya userA)
					UUID otherUserId = otherUserId(conversation, userId);
					GhostListenerIdentity ghostIdentity = resolvedGhostIdentities.get(otherUserId);

					// Ghost identity is authoritative and must never fall through to an
					// alternate professional profile in legacy/corrupt multi-profile data.
					boolean otherUserDeleted = erasedUserIds.contains(otherUserId);
					String otherUsername = otherUserDeleted ? "Silinmiş hesap" : ghostIdentity == null
							? getDisplayNameForUser(otherUserId)
							: ghostIdentity.username();
					String otherUserProfilePicture = otherUserDeleted ? null : ghostIdentity == null
							? getProfilePictureForUser(otherUserId)
							: ghostIdentity.profilePictureUrl();

					// son mesaj icerigi ve bilgileri
					String lastMessageContent = lastMessageOpt.map(DMMessage::getContent).orElse(null);
					String lastMessageType = lastMessageOpt.map(DMMessage::getMessageType).orElse(null);
					UUID lastMessageSenderId = lastMessageOpt.map(DMMessage::getSenderId).orElse(null);
					LocalDateTime lastMessageAt = lastMessageOpt.map(DMMessage::getCreatedAt).orElse(null);
					// okundu bilgisi (alici user ise ve readAt null ise okunmadi)
					Boolean lastMessageRead =
							lastMessageOpt.map(msg -> (msg.getRecipientId().equals(userId) &&  msg.getReadAt() == null) ? false : true)
									.orElse(null);

					return new DMConversationPreviewResponseDto(
							conversation.getId(),
							otherUserId,
							otherUsername,
							otherUserProfilePicture,
							lastMessageContent,
							lastMessageType,
							lastMessageSenderId,
							lastMessageAt,
							lastMessageRead,
							ghostIdentity == null ? null : ghostIdentity.visibilityMode(),
							otherUserDeleted
					);
				})
				.sorted(Comparator.comparing(DMConversationPreviewResponseDto :: lastMessageAt,
				                             Comparator.nullsLast(Comparator.reverseOrder())))
				.collect(Collectors.toList());
		return result;
 	}

	private UUID otherUserId(DMConversation conversation, UUID currentUserId) {
		return Objects.equals(conversation.getUserAId(), currentUserId)
				? conversation.getUserBId()
				: conversation.getUserAId();
	}


	// iki kisi arasinda var olan conversation'u bulur yoksa yeni conversation olusturur ve id'sini doner
	@Override
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public UUID getOrCreateConversation(UUID userAId, UUID userBId) {
		if(userAId.equals(userBId)) {
			throw new SoundConnectException(ErrorType.CANNOT_DM_SELF);
		}
		if (!userRepository.existsById(userBId)) {
			throw new SoundConnectException(ErrorType.USER_NOT_FOUND);
		}
		DmParticipantPair pair = DmParticipantPair.of(userAId, userBId);
		try {
			return conversationCreationService.getOrCreate(userAId, pair);
		} catch (DataIntegrityViolationException conflict) {
			// A concurrent request may have committed the same canonical pair.
			// The creation boundary owns a transaction including current-account
			// shared locks. It has rolled back before this nontransactional facade
			// retries, so the winner lookup gets fresh eligibility and clean state.
			for (int attempt = 1; attempt <= CONCURRENT_CREATE_LOOKUP_ATTEMPTS; attempt++) {
				Optional<UUID> winner = conversationCreationService.findExisting(userAId, pair);
				if (winner.isPresent()) {
					log.debug("Recovered concurrent DM conversation create pair=({}, {}) attempt={}",
					          pair.userAId(), pair.userBId(), attempt);
					return winner.get();
				}
			}
			throw conflict;
		}
	}



	// User'in hangi profile'a sahip oldugunu bilinmiyorsa tum profile repolarinda sirayla aratan yardimci metod
	// buluinca name doner bbulamazsa user'a sahip username'i doner
	private String getDisplayNameForUser(UUID userId) {
		Optional<String> venueName = venueRepository.findAllByOwnerId(userId)
		                                            .stream()
		                                            .map(venue -> safe(venue.getName()))
		                                            .filter(value -> !value.isBlank())
		                                            .findFirst();
		if (venueName.isPresent()) {
			return venueName.get();
		}

		Optional<String> username = userRepository.findById(userId)
		                                          .map(User::getUsername)
		                                          .map(this::safe)
		                                          .filter(value -> !value.isBlank());
		if (username.isPresent()) {
			return username.get();
		}
		return musicianProfileRepository.findByUserId(userId)
				.map(profile -> safe(profile.getName()))
				.or(() -> organizerProfileRepository.findByUserId(userId).map(profile -> safe(profile.getName())))
				.or(() -> producerProfileRepository.findByUserId(userId).map(profile -> safe(profile.getName())))
				.or(() -> studioProfileRepository.findByUserId(userId).map(profile -> safe(profile.getName())))
				.or(() -> listenerProfileRepository.findByUserId(userId).map(profile -> safe(profile.getName())))
				.or(() -> venueRepository.findAllByOwnerId(userId).stream().findFirst()
				.map(venue -> safe(venue.getName())))
				// Profile yoksa User'dan username al
				.orElseGet(() -> userRepository.findById(userId)
				.map(User::getUsername)
				.orElse("Bilinmeyen Kullanıcı"));

	}



	/**
	 * User'in hangi profile a sahip oldugunu bilmiyorsan tum profile repolarinda sirayla aratan yardimci metod
	 * bulunca profilePicture doner bulamazsa User'in profilePicture'i doner.
	 * FIXME ve ayrica diger profiller geldikce eklemeyi unutma
	 */
	private String getProfilePictureForUser(UUID userId) {
		return musicianProfileRepository.findByUserId(userId)
		                                .map(profile -> resolveProfilePicture(profile.getProfilePictureMediaId()))
		                                .or(() -> listenerProfileRepository.findByUserId(userId)
		                                                                   .map(profile -> resolveProfilePicture(profile.getProfilePictureMediaId())))
		                                .or(() -> organizerProfileRepository.findByUserId(userId)
		                                                                    .map(profile -> resolveProfilePicture(profile.getProfilePictureMediaId())))
		                                .or(() -> producerProfileRepository.findByUserId(userId)
		                                                                   .map(profile -> resolveProfilePicture(profile.getProfilePictureMediaId())))
		                                .or(() -> studioProfileRepository.findByUserId(userId)
		                                                                 .map(profile -> resolveProfilePicture(profile.getProfilePictureMediaId())))
		                                .or(() -> venueRepository.findAllByOwnerId(userId).stream().findFirst()
		                                                         .flatMap(venue -> venueProfileRepository.findByVenueId(venue.getId()))
		                                                         .map(profile -> resolveProfilePicture(profile.getProfilePictureMediaId())))
		                                .orElseGet(() -> userRepository.findById(userId)
		                                                               .map(User::getProfilePicture) // user fallback (ileride bunu da kaldıracağız)
		                                                               .orElse(null));
	}
	// helpers

	private String resolveProfilePicture(UUID mediaAssetId) {
		if (mediaAssetId == null) {
			return null;
		}
		try {
			return mediaAssetService.getDisplayUrl(mediaAssetId);
		} catch (Exception e) {
			log.warn("[DM] profile picture media not found mediaAssetId={}", mediaAssetId);
			return null;
		}
	}
	// null degerleri bos tringe cevirir. frontend icin hatasiz gonderim saglar
	private String safe(String value) {
		return value != null ? value : "";
	}
}
