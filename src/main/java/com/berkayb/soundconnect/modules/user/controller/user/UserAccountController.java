package com.berkayb.soundconnect.modules.user.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.user.dto.request.UsernameChangeRequestDto;
import com.berkayb.soundconnect.modules.user.service.UserService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.berkayb.soundconnect.shared.constant.EndPoints.User.BASE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.User.MY_USERNAME;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
public class UserAccountController {
	private final UserService userService;

	@PatchMapping(MY_USERNAME)
	public ResponseEntity<BaseResponse<String>> changeUsername(
			@AuthenticationPrincipal UserDetailsImpl principal,
			@RequestBody @Valid UsernameChangeRequestDto request
	) {
		String username = userService.changeUsername(principal.getId(), request);
		return ResponseEntity.ok(BaseResponse.<String>builder()
				.success(true)
				.code(200)
				.message("Kullanıcı adı güncellendi.")
				.data(username)
				.build());
	}
}
