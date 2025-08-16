package com.berkayb.soundconnect.modules.message.dm.service;


import com.berkayb.soundconnect.modules.message.dm.dto.response.DMConversationPreviewResponseDto;
import com.berkayb.soundconnect.modules.message.dm.entity.DMConversation;
import com.berkayb.soundconnect.modules.message.dm.entity.DMMessage;
import com.berkayb.soundconnect.modules.message.dm.repository.DMConversationRepository;
import com.berkayb.soundconnect.modules.message.dm.repository.DMMessageRepository;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.repository.ListenerProfileRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DMConversationServiceImpl implements DMConversationService {
	
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
	
	
	// kullanicinin dahil oldugu tum konusmalari ozet halinde getirir.
	// her konusma icin son mesaj ve karsi taraf bilgisi profile'dan alinir
	@Override
	public List<DMConversationPreviewResponseDto> getAllConversationsForUser(UUID userId) {
	// kullanicinin dahil oldugu tum conversationlari bul
		List<DMConversation> conversations = conversationRepository.findByUserAIdOrUserBId(userId, userId);
		
		// son mesaji ve karsi tarafi profile lookup ile bul
		List<DMConversationPreviewResponseDto> result = conversations.stream()
				.map(conversation -> {
					// son mesaji bul
					Optional<DMMessage> lastMessageOpt = messageRepository.findTopByConversationIdOrderByCreatedAtDesc(conversation.getId());
					
					// karsi tarafi bul (userB veya userA)
					UUID otherUserId = conversation.getUserAId().equals(userId) ? conversation.getUserBId() : conversation.getUserAId();
					
					// profile lookup
					String otherUsername = getDisplayNameForUser(otherUserId);
					String otherUserProfilePicture = getProfilePictureForUser(otherUserId);
					
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
							lastMessageRead
					);
				})
				.sorted(Comparator.comparing(DMConversationPreviewResponseDto :: lastMessageAt,
				                             Comparator.nullsLast(Comparator.reverseOrder())))
				.collect(Collectors.toList());
		return result;
 	}
	 
	 
	// iki kisi arasinda var olan conversation'u bulur yoksa yeni conversation olusturur ve id'sini doner
	@Override
	@Transactional
	public UUID getOrCreateConversation(UUID userAId, UUID userBId) {
		if(userAId.equals(userBId)) {
			throw new SoundConnectException(ErrorType.CANNOT_DM_SELF);
		}
		return conversationRepository.findConversationBetweenUsers(userAId, userBId)
				.map(DMConversation :: getId)
				.orElseGet(() -> {
					DMConversation conversation = DMConversation.builder()
							.userAId(userAId)
							.userBId(userBId)
							.build();
					conversationRepository.save(conversation);
					return conversation.getId();
				});
	}
	
	
	
	// User'in hangi profile'a sahip oldugunu bilinmiyorsa tum profile repolarinda sirayla aratan yardimci metod
	// buluinca name doner bbulamazsa user'a sahip username'i doner
	private String getDisplayNameForUser(UUID userId) {
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
	 */
	private String getProfilePictureForUser(UUID userId) {
		return musicianProfileRepository.findByUserId(userId)
				.map(profile -> safe(profile.getProfilePicture()))
				.or(() -> listenerProfileRepository.findByUserId(userId).map(profile -> safe(profile.getProfilePicture())))
				.or(() -> organizerProfileRepository.findByUserId(userId).map(profile -> safe(profile.getProfilePicture())))
				.or(() -> producerProfileRepository.findByUserId(userId).map(profile -> safe(profile.getProfilePicture())))
				.or(() -> studioProfileRepository.findByUserId(userId).map(profile -> safe(profile.getProfilePicture())))
				.or(() -> venueRepository.findAllByOwnerId(userId).stream().findFirst()
					.flatMap(venue -> venueProfileRepository.findByVenueId(venue.getId()))
					.map(profile -> safe(profile.getProfilePicture())))
				.orElseGet(() -> userRepository.findById(userId)
						.map(User :: getProfilePicture)
						.orElse(null));
		
		
		
		}
	// null degerleri bos tringe cevirir. frontend icin hatasiz gonderim saglar
	private String safe(String value) {
		return value != null ? value : "";
	}
}