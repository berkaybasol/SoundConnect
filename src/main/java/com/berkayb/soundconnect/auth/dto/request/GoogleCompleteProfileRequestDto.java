package com.berkayb.soundconnect.auth.dto.request;

import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GoogleCompleteProfileRequestDto(
		@NotBlank @Size(max = 16_384) String idToken,
		@NotBlank @Size(min = UsernameUtils.MIN_LENGTH, max = UsernameUtils.MAX_LENGTH) String username,
		@NotNull RoleEnum role,
		@AssertTrue(message = "Hesap oluşturmayı onaylamalısın.") boolean consent,
		String venueName, String venueAddress, String phone,
		String studioName, String studioAddress, String studioPhone,
		String cityId, String districtId, String neighborhoodId
) {
	public GoogleCompleteProfileRequestDto {
		username = UsernameUtils.normalize(username);
	}

	public GoogleCompleteProfileRequestDto(String idToken, String username, RoleEnum role, boolean consent) {
		this(idToken, username, role, consent, null, null, null, null, null, null, null, null, null);
	}
}
