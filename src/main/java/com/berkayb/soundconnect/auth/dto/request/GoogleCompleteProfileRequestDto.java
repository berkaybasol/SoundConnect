package com.berkayb.soundconnect.auth.dto.request;

import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import jakarta.validation.constraints.NotNull;


// Google ile kayit olan kullanicidan profil tamamlama asamasinda alinacak istek dto
public record GoogleCompleteProfileRequestDto(
		@NotNull (message = "Profil tipi (rol) secilmeli.")
		RoleEnum role
) {
}