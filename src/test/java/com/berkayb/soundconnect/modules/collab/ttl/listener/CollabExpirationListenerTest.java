package com.berkayb.soundconnect.modules.collab.ttl.listener;

import com.berkayb.soundconnect.modules.collab.entity.Collab;
import com.berkayb.soundconnect.modules.collab.repository.CollabRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CollabExpirationListener icin unit testler.
 * Redis Message -> onMessage -> repository davranisi test edilir.
 */
@ExtendWith(MockitoExtension.class)
class CollabExpirationListenerTest {
	
	@Mock
	CollabRepository collabRepository;
	
	@InjectMocks
	CollabExpirationListener listener;
	
	@Mock
	Message message;
	
	@Test
	@DisplayName("onMessage → collab:expire: ile baslamayan key'ler icin hicbir is yapmaz")
	void onMessage_nonCollabKey_doesNothing() {
		// given
		String key = "some-other-prefix:123";
		when(message.getBody()).thenReturn(key.getBytes(StandardCharsets.UTF_8));
		
		// when
		listener.onMessage(message);
		
		// then
		verifyNoInteractions(collabRepository);
	}
	
	@Test
	@DisplayName("onMessage → collab:expire: prefix'li ama gecersiz UUID icin hata yutulur, repo cagrilmaz")
	void onMessage_invalidUuid_doesNotCallRepository() {
		// given
		String key = "collab:expire:not-a-valid-uuid";
		when(message.getBody()).thenReturn(key.getBytes(StandardCharsets.UTF_8));
		
		// when / then (exception firlatilmamasi da onemli)
		assertThatNoException().isThrownBy(() -> listener.onMessage(message));
		
		verifyNoInteractions(collabRepository);
	}
	
	@Test
	@DisplayName("onMessage → gecerli UUID ama DB'de Collab yoksa delete cagirilmaz")
	void onMessage_collabNotFound_doesNotDelete() {
		// given
		UUID collabId = UUID.randomUUID();
		String key = "collab:expire:" + collabId;
		when(message.getBody()).thenReturn(key.getBytes(StandardCharsets.UTF_8));
		when(collabRepository.findById(collabId)).thenReturn(Optional.empty());
		
		// when
		listener.onMessage(message);
		
		// then
		verify(collabRepository).findById(collabId);
		verify(collabRepository, never()).delete(any(Collab.class));
	}
	
	@Test
	@DisplayName("onMessage → gecerli UUID ve DB'de Collab varsa hard delete yapar")
	void onMessage_collabFound_deletesCollab() {
		// given
		UUID collabId = UUID.randomUUID();
		String key = "collab:expire:" + collabId;
		when(message.getBody()).thenReturn(key.getBytes(StandardCharsets.UTF_8));
		
		Collab collab = Collab.builder().build();
		when(collabRepository.findById(collabId)).thenReturn(Optional.of(collab));
		
		// when
		listener.onMessage(message);
		
		// then
		verify(collabRepository).findById(collabId);
		verify(collabRepository).delete(collab);
	}
}