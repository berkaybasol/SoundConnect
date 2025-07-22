package com.berkayb.soundconnect.auth.controller;

import com.berkayb.soundconnect.auth.dto.request.LoginRequestDto;
import com.berkayb.soundconnect.auth.dto.request.RegisterRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.service.AuthService;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Auth.*;

@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Tag(name = "Auth Controller", description = "register, login includes transactions")
public class AuthControllerImpl implements AuthController {
	private final AuthService authService;
	
	
	@Override
	@PostMapping(REGISTER)
	public BaseResponse<LoginResponse> register(@RequestBody @Valid RegisterRequestDto registerRequestDto) {
		return authService.register(registerRequestDto);
	}
	
	
	@Override
	@PostMapping(LOGIN)                 // FIXME: buraya valid koymadik admin girisi kolayligi icin prod. da koyulcak
	public BaseResponse<LoginResponse> login(@RequestBody LoginRequestDto loginRequestDto) {
		return authService.login(loginRequestDto);
	}
}