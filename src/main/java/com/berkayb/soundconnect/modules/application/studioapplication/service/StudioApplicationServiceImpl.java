package com.berkayb.soundconnect.modules.application.studioapplication.service;

import com.berkayb.soundconnect.modules.application.studioapplication.dto.request.StudioApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.response.StudioApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.studioapplication.entity.StudioApplication;
import com.berkayb.soundconnect.modules.application.studioapplication.mapper.StudioApplicationMapper;
import com.berkayb.soundconnect.modules.application.studioapplication.repository.StudioApplicationRepository;
import com.berkayb.soundconnect.modules.application.studioapplication.support.StudioApplicationTimeProvider;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileProvisioningCommand;
import com.berkayb.soundconnect.modules.profile.StudioProfile.repository.StudioProfileRepository;
import com.berkayb.soundconnect.modules.profile.StudioProfile.service.StudioProfileService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.PageResponse;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudioApplicationServiceImpl implements StudioApplicationService {
	private static final int STUDIO_NAME_MAX_LENGTH = 100;
	private static final int STUDIO_ADDRESS_MAX_LENGTH = 255;
	private static final int APPLICATION_PAGE_MAX = 1000;
	private static final int APPLICATION_PAGE_SIZE_MAX = 100;
	private static final Pattern PHONE_CHARACTERS = Pattern.compile("^(?:\\+)?[0-9() .-]+$");
	private static final Pattern NON_PHONE_DIGITS = Pattern.compile("\\D");

	private final StudioApplicationRepository applicationRepository;
	private final StudioApplicationMapper applicationMapper;
	private final UserRepository userRepository;
	private final UserEntityFinder userEntityFinder;
	private final LocationEntityFinder locationEntityFinder;
	private final RoleRepository roleRepository;
	private final StudioProfileRepository studioProfileRepository;
	private final StudioProfileService studioProfileService;
	private final StudioApplicationAdminMailService studioApplicationAdminMailService;
	private final StudioApplicationTimeProvider timeProvider;

	@Override
	@Transactional
	public StudioApplicationResponseDto createApplication(
			UUID applicantUserId,
			StudioApplicationCreateRequestDto request
	) {
		User applicant = userRepository.findByIdForUpdate(applicantUserId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		if (hasStudioRole(applicant) || studioProfileRepository.existsByUserId(applicantUserId)) {
			throw new SoundConnectException(ErrorType.STUDIO_APPLICATION_ALREADY_EXISTS);
		}
		applicationRepository.findByApplicantAndStatus(applicant, ApplicationStatus.PENDING)
				.ifPresent(existing -> {
					throw new SoundConnectException(ErrorType.STUDIO_APPLICATION_ALREADY_EXISTS);
				});
		String studioName = normalizeAndLimitBusinessName(
				request.studioName(), "studioName", STUDIO_NAME_MAX_LENGTH);
		String studioAddress = normalizeAndLimit(
				request.studioAddress(), "studioAddress", STUDIO_ADDRESS_MAX_LENGTH);
		String phone = normalizePhone(request.phone());

		City city = locationEntityFinder.getCity(parseLocationId(request.cityId(), "cityId"));
		District district = locationEntityFinder.getDistrict(parseLocationId(request.districtId(), "districtId"));
		Neighborhood neighborhood = locationEntityFinder.getNeighborhood(
				parseLocationId(request.neighborhoodId(), "neighborhoodId")
		);
		validateHierarchy(city, district, neighborhood);

		StudioApplication application = StudioApplication.builder()
				.applicant(applicant)
				.studioName(studioName)
				.studioAddress(studioAddress)
				.phone(phone)
				.city(city)
				.district(district)
				.neighborhood(neighborhood)
				.status(ApplicationStatus.PENDING)
				.applicationDate(timeProvider.nowUtc())
				.build();
		StudioApplication saved = applicationRepository.save(application);
		studioApplicationAdminMailService.sendNewApplicationMail(saved);
		log.info("Studio application created applicantId={} applicationId={}", applicantUserId, saved.getId());
		return applicationMapper.toResponseDto(saved);
	}

	@Override
	@Transactional(readOnly = true)
	public PageResponse<StudioApplicationResponseDto> getApplicationsByUser(
			UUID applicantUserId,
			int page,
			int size
	) {
		Pageable pageable = applicationPageRequest(page, size, Sort.Direction.DESC);
		User applicant = userEntityFinder.getUser(applicantUserId);
		return PageResponse.from(
				applicationRepository.findAllByApplicant(applicant, pageable)
						.map(applicationMapper::toResponseDto));
	}

	@Override
	@Transactional(readOnly = true)
	public StudioApplicationResponseDto getPendingApplicationByUser(UUID applicantUserId) {
		User applicant = userEntityFinder.getUser(applicantUserId);
		return applicationRepository.findByApplicantAndStatus(applicant, ApplicationStatus.PENDING)
				.map(applicationMapper::toResponseDto)
				.orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_APPLICATION_NOT_FOUND));
	}

	@Override
	@Transactional(readOnly = true)
	public PageResponse<StudioApplicationResponseDto> getApplicationsByStatus(
			ApplicationStatus status,
			int page,
			int size
	) {
		Pageable pageable = applicationPageRequest(page, size, Sort.Direction.ASC);
		return PageResponse.from(
				applicationRepository.findAllByStatus(status, pageable)
						.map(applicationMapper::toResponseDto));
	}

	@Override
	@Transactional(readOnly = true)
	public StudioApplicationResponseDto getById(UUID applicationId) {
		return applicationMapper.toResponseDto(applicationRepository.findById(applicationId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_APPLICATION_NOT_FOUND)));
	}

	@Override
	@Transactional
	public StudioApplicationResponseDto approveApplication(UUID applicationId, UUID adminId) {
		StudioApplication application = pendingApplicationForUpdate(applicationId);
		User applicant = lockApplicant(application);
		Role studioRole = roleRepository.findByName(RoleEnum.ROLE_STUDIO.name())
				.orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
		Set<Role> roles = new HashSet<>(applicant.getRoles());
		roles.add(studioRole);
		applicant.setRoles(roles);
		applicant.setStatus(Boolean.TRUE.equals(applicant.getEmailVerified())
				? UserStatus.ACTIVE
				: UserStatus.INACTIVE);
		userRepository.save(applicant);

		studioProfileService.createApprovedProfile(new StudioProfileProvisioningCommand(
				applicant.getId(), application.getStudioName(), application.getStudioAddress(),
				application.getPhone(), application.getCity().getId(), application.getDistrict().getId(),
				application.getNeighborhood().getId()
		));

		application.setStatus(ApplicationStatus.APPROVED);
		application.setDecisionDate(timeProvider.nowUtc());
		application.setReviewedBy(resolveAdmin(adminId));
		application.setRejectionReason(null);
		StudioApplication saved = applicationRepository.save(application);
		studioApplicationAdminMailService.sendApplicantDecisionMail(saved);
		log.info("Studio application approved applicationId={} adminId={}", applicationId, adminId);
		return applicationMapper.toResponseDto(saved);
	}

	@Override
	@Transactional
	public StudioApplicationResponseDto rejectApplication(UUID applicationId, UUID adminId, String reason) {
		StudioApplication application = pendingApplicationForUpdate(applicationId);
		User applicant = lockApplicant(application);
		String normalizedReason = normalizeRequired(reason, "reason");
		if (normalizedReason.length() > 500) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "reason can be at most 500 characters");
		}
		application.setStatus(ApplicationStatus.REJECTED);
		application.setDecisionDate(timeProvider.nowUtc());
		application.setReviewedBy(resolveAdmin(adminId));
		application.setRejectionReason(normalizedReason);
		// Only accounts created by the Studio registration flow are closed. An
		// already-active multi-role user may submit a Studio application later;
		// rejecting that application must not disable their existing account.
		if (applicant.getStatus() == UserStatus.PENDING_STUDIO_REQUEST) {
			applicant.setStatus(UserStatus.REJECTED_STUDIO_REQUEST);
			userRepository.save(applicant);
		}
		StudioApplication saved = applicationRepository.save(application);
		studioApplicationAdminMailService.sendApplicantDecisionMail(saved);
		log.info("Studio application rejected applicationId={} adminId={}", applicationId, adminId);
		return applicationMapper.toResponseDto(saved);
	}

	private StudioApplication pendingApplicationForUpdate(UUID applicationId) {
		StudioApplication application = applicationRepository.findByIdForUpdate(applicationId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.STUDIO_APPLICATION_NOT_FOUND));
		if (application.getStatus() != ApplicationStatus.PENDING) {
			throw new SoundConnectException(ErrorType.INVALID_APPLICATION_STATUS);
		}
		return application;
	}

	private User lockApplicant(StudioApplication application) {
		User reference = application.getApplicant();
		if (reference == null || reference.getId() == null) {
			throw new SoundConnectException(ErrorType.USER_NOT_FOUND);
		}
		return userRepository.findByIdForUpdate(reference.getId())
				.orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
	}

	private User resolveAdmin(UUID adminId) {
		if (adminId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		return userEntityFinder.getUser(adminId);
	}

	private boolean hasStudioRole(User user) {
		return user.getRoles() != null && user.getRoles().stream()
				.anyMatch(role -> RoleEnum.ROLE_STUDIO.name().equals(role.getName()));
	}

	private void validateHierarchy(City city, District district, Neighborhood neighborhood) {
		if (!district.getCity().getId().equals(city.getId())) {
			throw new SoundConnectException(ErrorType.DISTRICT_CITY_MISMATCH);
		}
		if (!neighborhood.getDistrict().getId().equals(district.getId())) {
			throw new SoundConnectException(ErrorType.NEIGHBORHOOD_DISTRICT_MISMATCH);
		}
	}

	private UUID parseLocationId(String rawId, String fieldName) {
		try {
			return UUID.fromString(normalizeRequired(rawId, fieldName));
		} catch (IllegalArgumentException exception) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR, fieldName + " must be a valid UUID");
		}
	}

	private String normalizeRequired(String value, String fieldName) {
		if (value == null) throw new SoundConnectException(ErrorType.VALIDATION_ERROR, fieldName + " is required");
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
		if (normalized.isEmpty()) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR, fieldName + " is required");
		}
		return normalized;
	}

	private String normalizeAndLimitBusinessName(String value, String fieldName, int maxLength) {
		String normalized = UsernameUtils.normalize(
				normalizeRequired(value, fieldName));
		if (normalized == null || normalized.isBlank()) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					fieldName + " is required");
		}
		validateMaxLength(normalized, fieldName, maxLength);
		return normalized;
	}

	private String normalizeAndLimit(String value, String fieldName, int maxLength) {
		String normalized = normalizeRequired(value, fieldName);
		validateMaxLength(normalized, fieldName, maxLength);
		return normalized;
	}

	private void validateMaxLength(String value, String fieldName, int maxLength) {
		if (value.length() > maxLength) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					fieldName + " can be at most " + maxLength + " characters");
		}
	}

	private String normalizePhone(String rawPhone) {
		String normalized = normalizeRequired(rawPhone, "phone");
		if (!PHONE_CHARACTERS.matcher(normalized).matches()) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "phone is invalid");
		}
		String digits = NON_PHONE_DIGITS.matcher(normalized).replaceAll("");
		if (digits.length() == 10 && digits.startsWith("5")) {
			digits = "0" + digits;
		} else if (digits.length() == 12 && digits.startsWith("90")) {
			digits = "0" + digits.substring(2);
		}
		if (digits.length() != 11 || !digits.startsWith("0")) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					"phone must contain 11 digits and start with 0");
		}
		return digits;
	}

	private Pageable applicationPageRequest(int page, int size, Sort.Direction direction) {
		if (page < 0 || page > APPLICATION_PAGE_MAX
				|| size < 1 || size > APPLICATION_PAGE_SIZE_MAX) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					"page must be between 0 and " + APPLICATION_PAGE_MAX
							+ " and size must be between 1 and "
							+ APPLICATION_PAGE_SIZE_MAX);
		}
		return PageRequest.of(
				page,
				size,
				Sort.by(
						new Sort.Order(direction, "applicationDate"),
						new Sort.Order(direction, "id")
				)
		);
	}
}
