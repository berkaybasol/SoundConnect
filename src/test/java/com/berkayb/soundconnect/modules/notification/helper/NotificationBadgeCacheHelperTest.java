package com.berkayb.soundconnect.modules.notification.helper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit test: Redis'e bağlanmadan sadece StringRedisTemplate/ValueOperations mock'lanır.
 * Amaç: key formatı, set/decrement/get davranışları ve TTL kullanımını doğrulamak.
 */
@ExtendWith(MockitoExtension.class)
class NotificationBadgeCacheHelperTest {
	
	@Mock
	private StringRedisTemplate stringRedisTemplate;
	
	@Mock
	private ValueOperations<String, String> valueOps;
	
	private NotificationBadgeCacheHelper helper;
	private UUID userId;
	
	@BeforeEach
	void setUp() {
		// Burada STUB YOK → unreadKey testinde gereksiz stubbing hatasını önlüyoruz.
		helper = new NotificationBadgeCacheHelper(stringRedisTemplate);
		userId = UUID.randomUUID();
	}
	
	@Test
	@DisplayName("unreadKey: doğru Redis key formatını üretir")
	void unreadKey_formatsKeyCorrectly() {
		String key = helper.unreadKey(userId);
		assertThat(key).isEqualTo("unread_count:" + userId);
	}
	
	@Nested
	@DisplayName("setUnread")
	class SetUnread {
		
		@BeforeEach
		void wireOps() {
			when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		}
		
		@Test
		@DisplayName("pozitif değer TTL ile yazılır")
		void setUnread_positive() {
			helper.setUnread(userId, 7);
			
			verify(valueOps).set(
					eq("unread_count:" + userId),
					eq("7"),
					eq(NotificationBadgeCacheHelper.getUnreadCountTtl())
			);
		}
		
		@Test
		@DisplayName("negatif değer verilirse 0 yazılır")
		void setUnread_negativeClampedToZero() {
			helper.setUnread(userId, -5);
			
			verify(valueOps).set(
					eq("unread_count:" + userId),
					eq("0"),
					eq(NotificationBadgeCacheHelper.getUnreadCountTtl())
			);
		}
		
		@Test
		@DisplayName("Redis set sırasında exception swallow edilir (akış kırılmaz)")
		void setUnread_swallowExceptions() {
			doThrow(new RuntimeException("boom"))
					.when(valueOps)
					.set(anyString(), anyString(), any(Duration.class));
			
			// should not throw
			helper.setUnread(userId, 3);
		}
	}
	
	@Nested
	@DisplayName("decrementUnreadSafely")
	class DecrementUnreadSafely {
		
		@BeforeEach
		void wireOps() {
			when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		}
		
		@Test
		@DisplayName("cache YOKSA freshUnread - n (en az 0) olarak yazar")
		void decrement_noCache_usesFresh() {
			when(valueOps.get("unread_count:" + userId)).thenReturn(null);
			
			helper.decrementUnreadSafely(userId, 2, /*freshUnread*/ 1);
			
			// 1-2 => 0'a clamp
			verify(valueOps).set(
					eq("unread_count:" + userId),
					eq("0"),
					eq(NotificationBadgeCacheHelper.getUnreadCountTtl())
			);
		}
		
		@Test
		@DisplayName("cache VARSA current - n (en az 0) olarak yazar")
		void decrement_withCache_usesCurrent() {
			when(valueOps.get("unread_count:" + userId)).thenReturn("5");
			
			helper.decrementUnreadSafely(userId, 2, /*freshUnread ignored*/ 999);
			
			InOrder inOrder = inOrder(stringRedisTemplate, valueOps);
			inOrder.verify(stringRedisTemplate).opsForValue();
			inOrder.verify(valueOps).get("unread_count:" + userId);
			inOrder.verify(valueOps).set(
					eq("unread_count:" + userId),
					eq("3"),
					eq(NotificationBadgeCacheHelper.getUnreadCountTtl())
			);
		}
		
		@Test
		@DisplayName("current - n negatif olursa 0 yazılır")
		void decrement_clampsToZero() {
			when(valueOps.get("unread_count:" + userId)).thenReturn("1");
			
			helper.decrementUnreadSafely(userId, 5, 999);
			
			verify(valueOps).set(
					eq("unread_count:" + userId),
					eq("0"),
					eq(NotificationBadgeCacheHelper.getUnreadCountTtl())
			);
		}
		
		@Test
		@DisplayName("Redis okuma exception'ları swallow edilir")
		void decrement_swallowExceptions_onRead() {
			when(valueOps.get("unread_count:" + userId)).thenThrow(new RuntimeException("read failed"));
			
			// should not throw
			helper.decrementUnreadSafely(userId, 1, 10);
		}
	}
	
	@Nested
	@DisplayName("getCacheUnread")
	class GetCacheUnread {
		
		@BeforeEach
		void wireOps() {
			when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
		}
		
		@Test
		@DisplayName("cache varsa long parse edilip döner")
		void getCacheUnread_present() {
			when(valueOps.get("unread_count:" + userId)).thenReturn("42");
			
			Long got = helper.getCacheUnread(userId);
			
			assertThat(got).isEqualTo(42L);
		}
		
		@Test
		@DisplayName("cache yoksa null döner")
		void getCacheUnread_absent() {
			when(valueOps.get("unread_count:" + userId)).thenReturn(null);
			
			Long got = helper.getCacheUnread(userId);
			
			assertThat(got).isNull();
		}
		
		@Test
		@DisplayName("parse hatası/exception olursa null döner (akış kırılmaz)")
		void getCacheUnread_exceptionReturnsNull() {
			when(valueOps.get("unread_count:" + userId)).thenThrow(new RuntimeException("boom"));
			
			Long got = helper.getCacheUnread(userId);
			
			assertThat(got).isNull();
		}
	}
}