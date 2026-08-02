package com.berkayb.soundconnect.auth.dto.request;

import com.berkayb.soundconnect.shared.util.UsernameUtils;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UsernameAvailabilityRequestDto(
		@NotBlank(message = "Kullanıcı adı boş olamaz.")
		@Size(
				min = UsernameUtils.MIN_LENGTH,
				max = UsernameUtils.MAX_LENGTH,
				message = "Kullanıcı adı 3 ile 30 karakter arasında olmalıdır."
		)
		String username
) {
	public UsernameAvailabilityRequestDto {
		username = UsernameUtils.normalize(username);
	}
}
