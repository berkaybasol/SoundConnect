package com.berkayb.soundconnect.modules.notification.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.enums.NotificationType;
import com.berkayb.soundconnect.modules.notification.service.NotificationService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Notification.*;

@Slf4j
@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Tag(name = "NOTIFICATION / USER", description = "Kullanıcı bildirim")
public class NotificationController {
	
	private final NotificationService notificationService;
	
	// GET /api/v1/user/notifications?types=MEDIA,VENUE
	@GetMapping(LIST)
	public BaseResponse<Page<NotificationResponseDto>> listNotifications(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@RequestParam(value = "types", required = false) String typesCsv,
			@ParameterObject @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
	) {
		UUID userId = principal.getId();
		Page<NotificationResponseDto> page;
		if (typesCsv != null && !typesCsv.isBlank()) {
			Set<NotificationType> types = parseTypes(typesCsv);
			page = notificationService.getUserNotificationsByTypes(userId, types, pageable);
		} else {
			page = notificationService.getUserNotifications(userId, pageable);
		}
		return BaseResponse.<Page<NotificationResponseDto>>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Notifications fetched")
		                   .data(page)
		                   .build();
	}
	
	// GET /api/v1/user/notifications/recent
	@GetMapping(RECENT)
	public BaseResponse<List<NotificationResponseDto>> recent(
			@AuthenticationPrincipal UserDetailsImpl principal
	) {
		UUID userId = principal.getId();
		var list = notificationService.getRecentNotifications(userId);
		return BaseResponse.<List<NotificationResponseDto>>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Recent notifications fetched")
		                   .data(list)
		                   .build();
	}
	
	// GET /api/v1/user/notifications/unread-count
	@GetMapping(UNREAD_COUNT)
	public BaseResponse<Map<String, Long>> unreadCount(
			@AuthenticationPrincipal UserDetailsImpl principal
	) {
		UUID userId = principal.getId();
		long count = notificationService.getUnreadCount(userId);
		return BaseResponse.<Map<String, Long>>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Unread count fetched")
		                   .data(Map.of("unread", count))
		                   .build();
	}
	
	// POST /api/v1/user/notifications/{id}/read
	@PostMapping(MARK_READ)
	public BaseResponse<Void> markAsRead(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable("id") UUID notificationId
	) {
		UUID userId = principal.getId();
		notificationService.markAsRead(userId, notificationId);
		return BaseResponse.<Void>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Notification marked as read")
		                   .data(null)
		                   .build();
	}
	
	// POST /api/v1/user/notifications/read-all
	@PostMapping(MARK_ALL_READ)
	public BaseResponse<Map<String, Integer>> markAllAsRead(
			@AuthenticationPrincipal UserDetailsImpl principal
	) {
		UUID userId = principal.getId();
		int updated = notificationService.markAllAsRead(userId);
		return BaseResponse.<Map<String, Integer>>builder()
		                   .success(true)
		                   .code(200)
		                   .message("All unread notifications marked as read")
		                   .data(Map.of("updated", updated))
		                   .build();
	}
	
	// DELETE /api/v1/user/notifications/{id}
	@DeleteMapping(DELETE)
	public BaseResponse<Void> delete(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@PathVariable("id") UUID notificationId
	) {
		UUID userId = principal.getId();
		boolean deleted = notificationService.deleteById(userId, notificationId);
		return BaseResponse.<Void>builder()
		                   .success(deleted)
		                   .code(deleted ? 200 : 404)
		                   .message(deleted ? "Notification deleted" : "Notification not found")
		                   .data(null)
		                   .build();
	}
	
	// --- helpers ---
	private Set<NotificationType> parseTypes(String csv) {
		return Arrays.stream(csv.split(","))
		             .map(String::trim)
		             .filter(s -> !s.isEmpty())
		             .map(s -> {
			             try {
				             return NotificationType.valueOf(s);
			             } catch (IllegalArgumentException ex) {
				             log.warn("Unknown NotificationType ignored: {}", s);
				             return null;
			             }
		             })
		             .filter(Objects::nonNull)
		             .collect(Collectors.toCollection(() -> EnumSet.noneOf(NotificationType.class)));
	}
}