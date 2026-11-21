package com.berkayb.soundconnect.auth.service;

import com.berkayb.soundconnect.auth.dto.request.GoogleCompleteProfileRequestDto;
import com.berkayb.soundconnect.auth.dto.response.LoginResponse;
import com.berkayb.soundconnect.auth.security.JwtTokenProvider;
import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.request.StudioApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.studioapplication.service.StudioApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.request.VenueApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationService;
import com.berkayb.soundconnect.modules.application.venueapplication.service.VenueApplicationSessionAccess;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GoogleBusinessApplications {
	private final VenueApplicationService venues;
	private final StudioApplicationService studios;
	private final VenueApplicationSessionAccess venueSessions;
	private final JwtTokenProvider tokens;
	private final Validator validator;

	static RoleEnum applicantRole(UserStatus status) {
		if (status == UserStatus.PENDING_VENUE_REQUEST) return RoleEnum.ROLE_VENUE;
		if (status == UserStatus.PENDING_STUDIO_REQUEST || status == UserStatus.REJECTED_STUDIO_REQUEST) {
			return RoleEnum.ROLE_STUDIO;
		}
		return null;
	}

	LoginResponse submit(User user, GoogleCompleteProfileRequestDto form) {
		requireUnprivilegedApplicant(user);
		if (form.role() == RoleEnum.ROLE_VENUE) {
			var request = new VenueApplicationCreateRequestDto(form.venueName(), form.venueAddress(), form.phone(),
					form.cityId(), form.districtId(), form.neighborhoodId());
			var application = venues.createApplication(user.getId(), validated(request));
			return venueSession(user, application.id());
		}
		var request = new StudioApplicationCreateRequestDto(form.studioName(), form.studioAddress(), form.studioPhone(),
				form.cityId(), form.districtId(), form.neighborhoodId());
		var application = studios.createApplication(user.getId(), validated(request));
		return LoginResponse.pendingStudio(user, application.id());
	}

	LoginResponse resume(User user) {
		requireUnprivilegedApplicant(user);
		return switch (user.getStatus()) {
			case PENDING_VENUE_REQUEST -> venueSession(user, venueSessions.findSessionApplication(user)
					.orElseThrow(() -> new SoundConnectException(ErrorType.PENDING_VENUE_APPROVAL)));
			case PENDING_STUDIO_REQUEST -> LoginResponse.pendingStudio(user, studios.getPendingApplicationByUser(user.getId()).id());
			case REJECTED_STUDIO_REQUEST -> throw new SoundConnectException(ErrorType.STUDIO_APPLICATION_REJECTED);
			default -> throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		};
	}

	private static void requireUnprivilegedApplicant(User user) {
		if (!user.getRoles().isEmpty() || !user.getPermissions().isEmpty()) {
			throw new SoundConnectException(ErrorType.FORBIDDEN_ACCESS);
		}
	}

	private LoginResponse venueSession(User user, UUID applicationId) {
		String token = tokens.generateVenueApplicationToken(UserDetailsImpl.fromUser(user), applicationId);
		return LoginResponse.forVenueApplication(token, user, applicationId);
	}

	private <T> T validated(T request) {
		var errors = validator.validate(request);
		if (!errors.isEmpty()) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR,
					errors.stream().map(ConstraintViolation::getMessage).sorted().toList());
		}
		return request;
	}
}
