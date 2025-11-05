package com.berkayb.soundconnect.modules.notification.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Yalnızca NotificationController slice
@WebMvcTest(controllers = NotificationController.class)
@AutoConfigureMockMvc(addFilters = false) // security filtrelerini kapat
@Import(NotificationControllerTest.TestAuthPrincipalResolver.class)
class NotificationControllerTest {
	
	@Autowired
	private MockMvc mockMvc;
	
	// Controller’ın çağıracağı servis
	@MockitoBean
	private NotificationService notificationService;
	
	// --- Güvenlik tarafındaki bean’leri mock’la ki context yüklenebilsin ---
	@MockitoBean private com.berkayb.soundconnect.auth.security.JwtAuthenticationFilter jwtAuthenticationFilter;
	@MockitoBean private com.berkayb.soundconnect.auth.security.JwtTokenProvider jwtTokenProvider;
	
	private UUID userId;
	
	// Controller’daki base path: @RequestMapping(USER_BASE)
	// Projendeki sabit muhtemelen /api/v1/user/notifications
	private static final String BASE = "/api/v1/user/notifications";
	
	@BeforeEach
	void init() {
		userId = TestAuthPrincipalResolver.TEST_USER_ID;
	}
	
	@Test
	@DisplayName("GET /notifications → types olmadan sayfalı liste")
	void list_all_ok() throws Exception {
		NotificationResponseDto d1 = new NotificationResponseDto(UUID.randomUUID(), userId, NotificationType.MEDIA_UPLOAD_RECEVIED, "t1","m1", false, null, Map.of());
		NotificationResponseDto d2 = new NotificationResponseDto(UUID.randomUUID(), userId, NotificationType.SOCIAL_NEW_FOLLOWER, "t2","m2", true, null, Map.of());
		Page<NotificationResponseDto> page = new PageImpl<>(List.of(d1, d2));
		
		when(notificationService.getUserNotifications(eq(userId), any(Pageable.class))).thenReturn(page);
		
		mockMvc.perform(get(BASE))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.content.length()").value(2))
		       .andExpect(jsonPath("$.data.content[0].id").value(d1.id().toString()))
		       .andExpect(jsonPath("$.data.content[1].id").value(d2.id().toString()));
		
		verify(notificationService).getUserNotifications(eq(userId), any(Pageable.class));
		verifyNoMoreInteractions(notificationService);
	}
	
	@Test
	@DisplayName("GET /notifications?types=MEDIA_TRANSCODE_READY,SOCIAL_NEW_FOLLOWER → filtreli sayfalı liste")
	void list_with_types_ok() throws Exception {
		NotificationResponseDto d1 = new NotificationResponseDto(UUID.randomUUID(), userId, NotificationType.MEDIA_TRANSCODE_READY, "t1","m1", false, null, null);
		NotificationResponseDto d2 = new NotificationResponseDto(UUID.randomUUID(), userId, NotificationType.SOCIAL_NEW_FOLLOWER, "t2","m2", true, null, null);
		Page<NotificationResponseDto> page = new PageImpl<>(List.of(d1, d2));
		
		when(notificationService.getUserNotificationsByTypes(eq(userId), anyCollection(), any(Pageable.class))).thenReturn(page);
		
		mockMvc.perform(get(BASE).param("types", "MEDIA_TRANSCODE_READY,SOCIAL_NEW_FOLLOWER"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.content[0].type").value("MEDIA_TRANSCODE_READY"))
		       .andExpect(jsonPath("$.data.content[1].type").value("SOCIAL_NEW_FOLLOWER"));
		
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<NotificationType>> typeCap = ArgumentCaptor.forClass(Collection.class);
		verify(notificationService).getUserNotificationsByTypes(eq(userId), typeCap.capture(), any(Pageable.class));
		assertThat(typeCap.getValue())
				.containsExactlyInAnyOrder(NotificationType.MEDIA_TRANSCODE_READY, NotificationType.SOCIAL_NEW_FOLLOWER);
		
		verifyNoMoreInteractions(notificationService);
	}
	
	@Test
	@DisplayName("GET /notifications/recent → son 10 bildirim")
	void recent_ok() throws Exception {
		NotificationResponseDto d = new NotificationResponseDto(UUID.randomUUID(), userId, NotificationType.AUTH_EMAIL_VERIFIED, "t","m", false, null, null);
		when(notificationService.getRecentNotifications(userId)).thenReturn(List.of(d));
		
		mockMvc.perform(get(BASE + "/recent"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.length()").value(1))
		       .andExpect(jsonPath("$.data[0].id").value(d.id().toString()));
		
		verify(notificationService).getRecentNotifications(userId);
		verifyNoMoreInteractions(notificationService);
	}
	
	@Test
	@DisplayName("GET /notifications/unread-count → { unread: N }")
	void unread_count_ok() throws Exception {
		when(notificationService.getUnreadCount(userId)).thenReturn(5L);
		
		mockMvc.perform(get(BASE + "/unread-count"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.unread").value(5));
		
		verify(notificationService).getUnreadCount(userId);
		verifyNoMoreInteractions(notificationService);
	}
	
	@Test
	@DisplayName("POST /notifications/{id}/read → tek bildirim okundu")
	void mark_read_ok() throws Exception {
		UUID notifId = UUID.randomUUID();
		
		mockMvc.perform(post(BASE + "/" + notifId + "/read"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.message").value("Notification marked as read"));
		
		verify(notificationService).markAsRead(userId, notifId);
		verifyNoMoreInteractions(notificationService);
	}
	
	@Test
	@DisplayName("POST /notifications/read-all → tüm unread okundu")
	void mark_all_read_ok() throws Exception {
		when(notificationService.markAllAsRead(userId)).thenReturn(3);
		
		mockMvc.perform(post(BASE + "/read-all"))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.updated").value(3));
		
		verify(notificationService).markAllAsRead(userId);
		verifyNoMoreInteractions(notificationService);
	}
	
	@Test
	@DisplayName("DELETE /notifications/{id} → bulunduysa success=true, değilse success=false ve code=404")
	void delete_ok_and_notFound() throws Exception {
		UUID notifId1 = UUID.randomUUID();
		UUID notifId2 = UUID.randomUUID();
		
		when(notificationService.deleteById(userId, notifId1)).thenReturn(true);
		when(notificationService.deleteById(userId, notifId2)).thenReturn(false);
		
		mockMvc.perform(delete(BASE + "/" + notifId1))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200));
		
		mockMvc.perform(delete(BASE + "/" + notifId2))
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(false))
		       .andExpect(jsonPath("$.code").value(404));
		
		verify(notificationService).deleteById(userId, notifId1);
		verify(notificationService).deleteById(userId, notifId2);
		verifyNoMoreInteractions(notificationService);
	}
	
	// --- @AuthenticationPrincipal çözümü ---
	@TestConfiguration
	static class TestAuthPrincipalResolver implements WebMvcConfigurer {
		static final UUID TEST_USER_ID = UUID.randomUUID();
		
		@Override
		public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
			resolvers.add(new HandlerMethodArgumentResolver() {
				@Override
				public boolean supportsParameter(MethodParameter parameter) {
					return parameter.hasParameterAnnotation(AuthenticationPrincipal.class)
							&& parameter.getParameterType() == UserDetailsImpl.class;
				}
				
				@Override
				public Object resolveArgument(MethodParameter parameter,
				                              ModelAndViewContainer mavContainer,
				                              NativeWebRequest webRequest,
				                              WebDataBinderFactory binderFactory) {
					UserDetailsImpl principal = mock(UserDetailsImpl.class);
					when(principal.getId()).thenReturn(TEST_USER_ID);
					return principal;
				}
			});
		}
	}
}