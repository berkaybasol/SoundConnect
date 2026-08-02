package com.berkayb.soundconnect.modules.profile.shared.factory;

import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileService;
import com.berkayb.soundconnect.modules.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StudioProfileCreatorTest {

	@Mock
	private StudioProfileService studioProfileService;

	@Test
	void registrationUsesUsernameAsTheRequiredInitialStudioName() {
		UUID userId = UUID.randomUUID();
		User user = new User();
		user.setId(userId);
		user.setUsername("faruk-studio");

		new StudioProfileCreator(studioProfileService).createProfile(user);

		ArgumentCaptor<StudioProfileSaveRequestDto> request =
				ArgumentCaptor.forClass(StudioProfileSaveRequestDto.class);
		verify(studioProfileService).createProfile(org.mockito.ArgumentMatchers.eq(userId), request.capture());
		assertThat(request.getValue().name()).isEqualTo("faruk-studio");
	}
}
