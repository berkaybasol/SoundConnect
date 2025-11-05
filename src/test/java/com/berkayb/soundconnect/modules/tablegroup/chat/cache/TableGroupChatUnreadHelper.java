package com.berkayb.soundconnect.modules.tablegroup.chat.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TableGroupChatUnreadHelperTest {
	
	@Mock
	private StringRedisTemplate redisTemplate;
	
	@Mock
	private ValueOperations<String, String> valueOperations;
	
	@InjectMocks
	private TableGroupChatUnreadHelper unreadHelper;
	
	@Test
	void incrementUnread_shouldIncrementValueForGivenUserAndTableGroup() {
		// given
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		
		String expectedKey = "table-group:chat:unread:" + userId + ":" + tableGroupId;
		
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		
		// when
		unreadHelper.incrementUnread(userId, tableGroupId);
		
		// then
		verify(redisTemplate).opsForValue();
		verify(valueOperations).increment(expectedKey);
		verifyNoMoreInteractions(redisTemplate, valueOperations);
	}
	
	@Test
	void resetUnread_shouldDeleteKeyForGivenUserAndTableGroup() {
		// given
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		
		String expectedKey = "table-group:chat:unread:" + userId + ":" + tableGroupId;
		
		// when
		unreadHelper.resetUnread(userId, tableGroupId);
		
		// then
		verify(redisTemplate).delete(expectedKey);
		verifyNoMoreInteractions(redisTemplate);
	}
	
	@Test
	void getUnread_whenValueExists_shouldReturnParsedInteger() {
		// given
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		
		String expectedKey = "table-group:chat:unread:" + userId + ":" + tableGroupId;
		
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(expectedKey)).thenReturn("7");
		
		// when
		int unread = unreadHelper.getUnread(userId, tableGroupId);
		
		// then
		assertThat(unread).isEqualTo(7);
		
		verify(redisTemplate).opsForValue();
		verify(valueOperations).get(expectedKey);
		verifyNoMoreInteractions(redisTemplate, valueOperations);
	}
	
	@Test
	void getUnread_whenValueIsNull_shouldReturnZero() {
		// given
		UUID userId = UUID.randomUUID();
		UUID tableGroupId = UUID.randomUUID();
		
		String expectedKey = "table-group:chat:unread:" + userId + ":" + tableGroupId;
		
		when(redisTemplate.opsForValue()).thenReturn(valueOperations);
		when(valueOperations.get(expectedKey)).thenReturn(null);
		
		// when
		int unread = unreadHelper.getUnread(userId, tableGroupId);
		
		// then
		assertThat(unread).isEqualTo(0);
		
		verify(redisTemplate).opsForValue();
		verify(valueOperations).get(expectedKey);
		verifyNoMoreInteractions(redisTemplate, valueOperations);
	}
}