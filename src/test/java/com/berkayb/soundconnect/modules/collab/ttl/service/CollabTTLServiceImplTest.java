package com.berkayb.soundconnect.modules.collab.ttl.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * CollabTTLServiceImpl icin unit testler.
 * RedisTemplate mock'lanarak dogru key ve TTL davranisi test edilir.
 */
@ExtendWith(MockitoExtension.class)
class CollabTTLServiceImplTest {
	
	private static final String KEY_PREFIX = "collab:expire:";
	
	@Mock
	RedisTemplate<String, String> redisTemplate;
	
	@Mock
	ValueOperations<String, String> valueOps;
	
	@InjectMocks
	CollabTTLServiceImpl collabTTLService;
	
	@Test
	@DisplayName("setTTL → expirationTime null ise Redis'e hic yazmaz")
	void setTTL_nullExpiration_doesNothing() {
		UUID collabId = UUID.randomUUID();
		
		collabTTLService.setTTL(collabId, null);
		
		verifyNoInteractions(redisTemplate);
	}
	
	@Test
	@DisplayName("setTTL → gecmis tarih icin TTL yazmaz")
	void setTTL_pastExpiration_doesNotWrite() {
		UUID collabId = UUID.randomUUID();
		LocalDateTime past = LocalDateTime.now().minusMinutes(5);
		
		collabTTLService.setTTL(collabId, past);
		
		verify(redisTemplate, never()).opsForValue();
		verify(redisTemplate, never()).delete(anyString());
	}
	
	@Test
	@DisplayName("setTTL → gelecekteki tarih icin Redis key + TTL yazar")
	void setTTL_futureExpiration_writesToRedis() {
		UUID collabId = UUID.randomUUID();
		LocalDateTime future = LocalDateTime.now().plusMinutes(10);
		String expectedKey = KEY_PREFIX + collabId;
		
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		
		collabTTLService.setTTL(collabId, future);
		
		verify(redisTemplate).opsForValue();
		verify(valueOps).set(eq(expectedKey), eq("1"), any(Duration.class));
	}
	
	@Test
	@DisplayName("resetTTL → mevcut TTL'i siler sonra yeni TTL yazar")
	void resetTTL_deletesOldAndSetsNew() {
		UUID collabId = UUID.randomUUID();
		LocalDateTime future = LocalDateTime.now().plusMinutes(15);
		String expectedKey = KEY_PREFIX + collabId;
		
		when(redisTemplate.opsForValue()).thenReturn(valueOps);
		
		collabTTLService.resetTTL(collabId, future);
		
		// once delete, sonra set
		verify(redisTemplate).delete(expectedKey);
		verify(redisTemplate).opsForValue();
		verify(valueOps).set(eq(expectedKey), eq("1"), any(Duration.class));
	}
	
	@Test
	@DisplayName("deleteTTL → ilgili Redis key'ini siler")
	void deleteTTL_callsRedisDelete() {
		UUID collabId = UUID.randomUUID();
		String expectedKey = KEY_PREFIX + collabId;
		
		when(redisTemplate.delete(expectedKey)).thenReturn(true);
		
		collabTTLService.deleteTTL(collabId);
		
		verify(redisTemplate).delete(expectedKey);
	}
}