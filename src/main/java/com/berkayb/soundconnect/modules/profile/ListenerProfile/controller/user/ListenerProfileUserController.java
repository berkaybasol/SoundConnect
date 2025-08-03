package com.berkayb.soundconnect.modules.profile.ListenerProfile.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.request.ListenerSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.dto.response.ListenerProfileResponseDto;
import com.berkayb.soundconnect.modules.profile.ListenerProfile.service.ListenerProfileService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import static com.berkayb.soundconnect.shared.constant.EndPoints.ListenerProfile.*;

/**
 * * @AuthenticationPrincipal açıklaması:
 *
 *  * - Spring Security, authenticated (giriş yapmış) kullanıcının kimliğini (Principal)
 *  *   otomatik olarak controller metoduna enjekte etmemizi sağlar.
 *  * - Bu annotation ile JWT’den veya session’dan doğrulanan kullanıcıyı doğrudan parametre olarak alırız.
 *  * - Yani request’te userId gibi hassas bir bilgiyi taşımak zorunda kalmayız.
 *  * - Arka planda SecurityContext’in içindeki Principal nesnesi burada UserDetailsImpl olarak gelir.
 *  *
 *  * Avantajları:
 *  *   - Kullanıcı manipülasyonunu engeller (başkasının userId’sini göndermeye kalkamaz).
 *  *   - Her endpointte id çekmekle uğraşmaz, güvenli ve sade bir kod üretiriz.
 *  *   - IDE ile otomatik tamamlama ve Swagger/OpenAPI dokümantasyonuna daha net yansır.
 */

//TODO DENEME KDSGKDSKLGDSKL
@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@Tag(name = "User / Listener Profile", description = "Operations related to the profile of users with a Listener " +
		"profile")
public class ListenerProfileUserController {
	private final ListenerProfileService listenerProfileService;
	
	// getir
	@GetMapping(ME)
	public ResponseEntity<BaseResponse<ListenerProfileResponseDto>> getMyProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails) {
		ListenerProfileResponseDto response = listenerProfileService.getProfileByUserId(userDetails.getUser().getId());
		return ResponseEntity.ok(BaseResponse.<ListenerProfileResponseDto>builder()
		                                     .success(true).code(200).message("Profil getirildi").data(response).build());
	}
	
	// olustur
	@PostMapping(CREATE)
	public ResponseEntity<BaseResponse<ListenerProfileResponseDto>> createMyProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestBody ListenerSaveRequestDto dto) {
		ListenerProfileResponseDto response = listenerProfileService.createProfile(userDetails.getUser().getId(), dto);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileResponseDto>builder()
		                                     .success(true).code(201).message("Profil oluşturuldu").data(response).build());
	}
	
	// guncelle
	@PutMapping(UPDATE)
	public ResponseEntity<BaseResponse<ListenerProfileResponseDto>> updateMyProfile(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestBody ListenerSaveRequestDto dto) {
		ListenerProfileResponseDto response = listenerProfileService.updateProfile(userDetails.getUser().getId(), dto);
		return ResponseEntity.ok(BaseResponse.<ListenerProfileResponseDto>builder()
		                                     .success(true).code(200).message("Profil güncellendi").data(response).build());
	}
	
}