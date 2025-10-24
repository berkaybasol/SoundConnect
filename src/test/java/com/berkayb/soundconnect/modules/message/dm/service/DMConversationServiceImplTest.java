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
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
@Tag("unit")

class DMConversationServiceImplTest {
	
	@InjectMocks
	DMConversationServiceImpl service;
	
	@Mock DMConversationRepository conversationRepository;
	@Mock DMMessageRepository messageRepository;
	@Mock UserRepository userRepository;
	
	// Profil zinciri mock'ları
	@Mock MusicianProfileRepository musicianProfileRepository;
	@Mock VenueProfileRepository venueProfileRepository;
	@Mock OrganizerProfileRepository organizerProfileRepository;
	@Mock ListenerProfileRepository listenerProfileRepository;
	@Mock ProducerProfileRepository producerProfileRepository;
	@Mock StudioProfileRepository studioProfileRepository;
	@Mock VenueRepository venueRepository;
	
	@Test
	@DisplayName("getAllConversationsForUser: son mesaja göre DESC sıralama + profil lookup + okundu bayrağı")
	void getAllConversationsForUser_profileLookup_sorting_and_readFlag() {
		UUID currentUser = UUID.randomUUID();
		UUID other1 = UUID.randomUUID();
		UUID other2 = UUID.randomUUID();
		
		// İki konuşma: currentUser—other1 ve currentUser—other2
		DMConversation c1 = DMConversation.builder()
		                                  .id(UUID.randomUUID())
		                                  .userAId(currentUser)
		                                  .userBId(other1)
		                                  .build();
		DMConversation c2 = DMConversation.builder()
		                                  .id(UUID.randomUUID())
		                                  .userAId(other2)
		                                  .userBId(currentUser)
		                                  .build();
		
		when(conversationRepository.findByUserAIdOrUserBId(currentUser, currentUser))
				.thenReturn(List.of(c1, c2));
		
		// c1 için SON MESAJ (DAHA ESKİ) – createdAt deterministik:
		DMMessage lastC1 = mock(DMMessage.class);
		when(lastC1.getId()).thenReturn(UUID.randomUUID());
		when(lastC1.getConversationId()).thenReturn(c1.getId());
		when(lastC1.getSenderId()).thenReturn(other1);
		when(lastC1.getRecipientId()).thenReturn(currentUser);
		when(lastC1.getContent()).thenReturn("hey from other1");
		when(lastC1.getMessageType()).thenReturn("text");
		when(lastC1.getReadAt()).thenReturn(null); // unread
		when(lastC1.getCreatedAt()).thenReturn(LocalDateTime.now().minusMinutes(10));
		when(messageRepository.findTopByConversationIdOrderByCreatedAtDesc(c1.getId()))
				.thenReturn(Optional.of(lastC1));
		
		// c2 için SON MESAJ (DAHA YENİ) – createdAt deterministik:
		DMMessage lastC2 = mock(DMMessage.class);
		when(lastC2.getId()).thenReturn(UUID.randomUUID());
		when(lastC2.getConversationId()).thenReturn(c2.getId());
		when(lastC2.getSenderId()).thenReturn(currentUser);
		when(lastC2.getRecipientId()).thenReturn(other2);
		when(lastC2.getContent()).thenReturn("hello other2");
		when(lastC2.getMessageType()).thenReturn("text");
		when(lastC2.getReadAt()).thenReturn(LocalDateTime.now()); // read
		when(lastC2.getCreatedAt()).thenReturn(LocalDateTime.now());
		when(messageRepository.findTopByConversationIdOrderByCreatedAtDesc(c2.getId()))
				.thenReturn(Optional.of(lastC2));
		
		doReturn(Optional.empty()).when(musicianProfileRepository).findByUserId(any());
		doReturn(Optional.empty()).when(organizerProfileRepository).findByUserId(any());
		doReturn(Optional.empty()).when(producerProfileRepository).findByUserId(any());
		doReturn(Optional.empty()).when(studioProfileRepository).findByUserId(any());
		doReturn(Optional.empty()).when(listenerProfileRepository).findByUserId(any());
		doReturn(Collections.emptyList()).when(venueRepository).findAllByOwnerId(any());
		doReturn(Optional.empty()).when(venueProfileRepository).findByVenueId(any());
		
		// User fallback
		User u2 = mock(User.class);
		when(u2.getId()).thenReturn(other2);
		when(u2.getUsername()).thenReturn("other2_user");
		when(u2.getProfilePicture()).thenReturn("pp://other2");
		when(userRepository.findById(other2)).thenReturn(Optional.of(u2));
		
		// Ayrıca other1 için isim/pp bekliyorsak ve MusicianProfile'ın getter isimlerini bilmiyorsak
		// doğrudan service'in "safe" davranışı nedeniyle en azından empty string dönmesi gerekir.
		// Sen "Guitar Wizard" ve "pp://guitar" görmek istiyorsan, username fallback ile de sağlayabiliriz:
		// userRepository.findById(other1) fallback'ine düşmesini istemiyorsan aşağıdaki iki satırı aç:
		User u1 = mock(User.class);
		when(u1.getId()).thenReturn(other1);
		when(u1.getUsername()).thenReturn("Guitar Wizard");
		when(u1.getProfilePicture()).thenReturn("pp://guitar");
		when(userRepository.findById(other1)).thenReturn(Optional.of(u1));
		
		// Çıktı
		List<DMConversationPreviewResponseDto> out = service.getAllConversationsForUser(currentUser);
		
		// Beklentiler:
		// 1) Sıralama: last message'ı daha yeni olan c2 önce gelmeli.
		assertThat(out).hasSize(2);
		assertThat(out.get(0).conversationId()).isEqualTo(c2.getId());
		assertThat(out.get(1).conversationId()).isEqualTo(c1.getId());
		
		// 2) c1 → other1 bilgileri (username fallback ile "Guitar Wizard")
		DMConversationPreviewResponseDto prevC1 = out.stream()
		                                             .filter(p -> p.conversationId().equals(c1.getId()))
		                                             .findFirst().orElseThrow();
		assertThat(prevC1.otherUserId()).isEqualTo(other1);
		assertThat(prevC1.otherUsername()).isEqualTo("Guitar Wizard");       // fallback user
		assertThat(prevC1.otherUserProfilePicture()).isEqualTo("pp://guitar"); // fallback user
		assertThat(prevC1.lastMessageContent()).isEqualTo("hey from other1");
		// currentUser recipient ve readAt=null → unread → lastMessageRead=false
		assertThat(prevC1.lastMessageRead()).isFalse();
		
		// 3) c2 → other2 fallback user
		DMConversationPreviewResponseDto prevC2 = out.stream()
		                                             .filter(p -> p.conversationId().equals(c2.getId()))
		                                             .findFirst().orElseThrow();
		assertThat(prevC2.otherUserId()).isEqualTo(other2);
		assertThat(prevC2.otherUsername()).isEqualTo("other2_user");
		assertThat(prevC2.otherUserProfilePicture()).isEqualTo("pp://other2");
		assertThat(prevC2.lastMessageContent()).isEqualTo("hello other2");
		// last message sender currentUser, recipient other2 → currentUser açısından okundu=true
		assertThat(prevC2.lastMessageRead()).isTrue();
	}
	
	@Test
	@DisplayName("getOrCreateConversation: varsa mevcut ID, yoksa yeni oluşturur")
	void getOrCreateConversation_createOrReturnExisting() {
		UUID u1 = UUID.randomUUID();
		UUID u2 = UUID.randomUUID();
		
		// Case-1: Mevcut var → direkt id dön
		DMConversation existing = DMConversation.builder()
		                                        .id(UUID.randomUUID())
		                                        .userAId(u1)
		                                        .userBId(u2)
		                                        .build();
		when(conversationRepository.findConversationBetweenUsers(u1, u2))
				.thenReturn(Optional.of(existing));
		
		UUID id1 = service.getOrCreateConversation(u1, u2);
		assertThat(id1).isEqualTo(existing.getId());
		verify(conversationRepository, never()).save(any());
		
		// Case-2: Yok → yeni oluştur (save sırasında id ata)
		when(conversationRepository.findConversationBetweenUsers(u1, u2))
				.thenReturn(Optional.empty());
		
		when(conversationRepository.save(any(DMConversation.class)))
				.thenAnswer(inv -> {
					DMConversation c = inv.getArgument(0);
					c.setId(UUID.randomUUID()); // DB'nin vereceği id'yi biz veriyoruz
					return c;
				});
		
		UUID id2 = service.getOrCreateConversation(u1, u2);
		assertThat(id2).isNotNull();
		
		// Ek kontrol: doğru userA/userB ile kaydetmiş miyiz?
		ArgumentCaptor<DMConversation> captor = ArgumentCaptor.forClass(DMConversation.class);
		verify(conversationRepository).save(captor.capture());
		DMConversation saved = captor.getValue();
		assertThat(saved.getUserAId()).isEqualTo(u1);
		assertThat(saved.getUserBId()).isEqualTo(u2);
	}
	
	@Test
	@DisplayName("getOrCreateConversation: self-DM engellenmeli")
	void getOrCreateConversation_selfBlocked() {
		UUID u = UUID.randomUUID();
		assertThrows(SoundConnectException.class, () -> service.getOrCreateConversation(u, u));
		verify(conversationRepository, never()).findConversationBetweenUsers(any(), any());
		verify(conversationRepository, never()).save(any());
	}
}