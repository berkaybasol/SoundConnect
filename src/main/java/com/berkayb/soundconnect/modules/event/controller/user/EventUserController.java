package com.berkayb.soundconnect.modules.event.controller.user;


import com.berkayb.soundconnect.modules.event.dto.response.EventResponseDto;
import com.berkayb.soundconnect.modules.event.service.EventService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Event.*;


@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "FOR USERS / Events (Discover)", description = "Kullanıcılar için etkinlik listeleme servisleri")
public class EventUserController {

	private final EventService eventService;
	
	
	@GetMapping(USER_BY_ID)
	@Operation(summary = "Etkinlik detayini getirir")
	public ResponseEntity<BaseResponse<EventResponseDto>> getEventById(@PathVariable UUID eventId) {
		log.info("Etkinlik detayi getiriliyor. eventId={}", eventId);
		
		var dto = eventService.getEventById(eventId);
		
		return ResponseEntity.ok(BaseResponse.<EventResponseDto>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Etkinlik detaylari getirildi.")
				                         .data(dto)
				                         .build());
	}
	
	@GetMapping(USER_TODAY)
	@Operation(summary = "Bugun gerceklesen tum etkinlikleri getirir")
	public ResponseEntity<BaseResponse<List<EventResponseDto>>> getTodayEvents(){
		log.info("Bugunku etkinlikler listeleniyor");
		var list = eventService.getEventsByDate(LocalDate.now());
		
		return ResponseEntity.ok(BaseResponse.<List<EventResponseDto>>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Bugunku etkinlikler listelendi")
				                         .data(list)
				                         .build());
	}
	
	@GetMapping(USER_BY_DATE)
	@Operation(summary = "Belirli bir tarihteki tum etkinlikleri getirir")
	public ResponseEntity<BaseResponse<List<EventResponseDto>>> getEventsByDate(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date){
		log.info("Belirtilen tarihteki etkinlikler listeleniyor. date={}", date);
		
		var list = eventService.getEventsByDate(date);
		
		return ResponseEntity.ok(BaseResponse.<List<EventResponseDto>>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Etkinlikler listelendi")
				                         .data(list)
				                         .build());
	}
	
	@GetMapping(USER_BY_CITY)
	@Operation(summary = "Sehre gore tum etkinlikleri getirir")
	public ResponseEntity<BaseResponse<List<EventResponseDto>>> getEventsByCity(@PathVariable UUID cityId){
		log.info("Sehre gore tum etkinlikler listeleniyor. cityId={}", cityId);
		
		var list = eventService.getEventsByCity(cityId);
		
		return ResponseEntity.ok(BaseResponse.<List<EventResponseDto>>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Sehirdeki etkinlikler listelendi")
				                         .data(list)
				                         .build());
	}
	
	@GetMapping(USER_BY_DISTRICT)
	@Operation(summary = "Ilceye gore tum etkinlikleri getirir")
	public ResponseEntity<BaseResponse<List<EventResponseDto>>> getEventsByDistrict(@PathVariable UUID districtId){
		
		log.info("Ilceye gore tum etkinlikler listeleniyor. districtId={}", districtId);
		
		var list = eventService.getEventsByDistrict(districtId);
		
		return ResponseEntity.ok(BaseResponse.<List<EventResponseDto>>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Ilcedeki etkinlikler listelendi")
				                         .data(list)
				                         .build());
	}
	
	@GetMapping(USER_BY_NEIGHBORHOOD)
	@Operation(summary = "Mahalleye gore tum etkinlikleri getir.")
	public ResponseEntity<BaseResponse<List<EventResponseDto>>> getEventsByNeighborhood(@PathVariable UUID neighborhoodId){
		log.info("Mahalleye gore tum etkinlikler listeleniyor. neighborhoodId={}", neighborhoodId);
		
		var list = eventService.getEventsByNeighborhood(neighborhoodId);
		return ResponseEntity.ok(BaseResponse.<List<EventResponseDto>>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Mahalledeki etkinlikler listelendi")
				                         .data(list)
				                         .build());
	}
	
	@GetMapping(USER_BY_VENUE)
	@Operation(summary = "Bir mekandaki tum etkinlikleri getirir")
	public ResponseEntity<BaseResponse<List<EventResponseDto>>> getEventsByVenue(@PathVariable UUID venueId){
		log.info("Mekana gore etkinlikler listeleniyor. venueId={}", venueId);
		
		var list = eventService.getEventsByVenue(venueId);
		return ResponseEntity.ok(BaseResponse.<List<EventResponseDto>>builder()
				                         .success(true)
				                         .code(200)
				                         .message("Mekandeki etkinlikler listelendi")
				                         .data(list)
				                         .build());
	}
}