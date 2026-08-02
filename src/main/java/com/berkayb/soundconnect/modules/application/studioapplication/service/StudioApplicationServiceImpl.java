package com.berkayb.soundconnect.modules.application.studioapplication.service;

import com.berkayb.soundconnect.modules.application.studioapplication.dto.request.StudioApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.studioapplication.dto.response.StudioApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.studioapplication.entity.StudioApplication;
import com.berkayb.soundconnect.modules.application.studioapplication.mapper.StudioApplicationMapper;
import com.berkayb.soundconnect.modules.application.studioapplication.repository.StudioApplicationRepository;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.StudioProfile.dto.request.StudioProfileProvisioningCommand;
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
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StudioApplicationServiceImpl implements StudioApplicationService {
	private final StudioApplicationRepository applicationRepository;
	private final StudioApplicationMapper applicationMapper;
	private final UserRepository userRepository;
	private final UserEntityFinder userEntityFinder;
	private final LocationEntityFinder locationEntityFinder;
	private final RoleRepository roleRepository;
	private final StudioProfileService studioProfileService;
	private final StudioApplicationAdminMailService studioApplicationAdminMailService;

	@Override
	@Transactional
	public StudioApplicationResponseDto createApplication(
			UUID applicantUserId,
			StudioApplicationCreateRequestDto request
	) {
		User applicant = userRepository.findByIdForUpdate(applicantUserId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		applicationRepository.findByApplicantAndStatus(applicant, ApplicationStatus.PENDING)
				.ifPresent(existing -> {
					throw new SoundConnectException(ErrorType.STUDIO_APPLICATION_ALREADY_EXISTS);
				});

		City city = locationEntityFinder.getCity(parseLocationId(request.cityId(), "cityId"));
		District district = locationEntityFinder.getDistrict(parseLocationId(request.districtId(), "districtId"));
		Neighborhood neighborhood = locationEntityFinder.getNeighborhood(
				parseLocationId(request.neighborhoodId(), "neighborhoodId")
		);
		validateHierarchy(city, district, neighborhood);

		StudioApplication application = StudioApplication.builder()
				.applicant(applicant)
				.studioName(normalizeBusinessName(request.studioName(), "studioName"))
				.studioAddress(normalizeRequired(request.studioAddress(), "studioAddress"))
				.phone(normalizeRequired(request.phone(), "phone"))
				.city(city)
				.district(district)
				.neighborhood(neighborhood)
				.status(ApplicationStatus.PENDING)
				.applicationDate(LocalDateTime.now())
				.build();
		StudioApplication saved = applicationRepository.save(application);
		studioApplicationAdminMailService.sendNewApplicationMail(saved);
		log.info("Studio application created applicantId={} applicationId={}", applicantUserId, saved.getId());
		return applicationMapper.toResponseDto(saved);
	}

	@Override
	@Transactional(readOnly = true)
	public List<StudioApplicationResponseDto> getApplicationsByUser(UUID applicantUserId) {
		User applicant = userEntityFinder.getUser(applicantUserId);
		return applicationRepository.findAllByApplicantOrderByApplicationDateDesc(applicant)
				.stream().map(applicationMapper::toResponseDto).toList();
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
	public List<StudioApplicationResponseDto> getApplicationsByStatus(ApplicationStatus status) {
		return applicationRepository.findAllByStatusOrderByApplicationDateAsc(status)
				.stream().map(applicationMapper::toResponseDto).toList();
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
		User applicant = application.getApplicant();
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
		application.setDecisionDate(LocalDateTime.now());
		application.setReviewedBy(resolveAdmin(adminId));
		application.setRejectionReason(null);
		StudioApplication saved = applicationRepository.save(application);
		log.info("Studio application approved applicationId={} adminId={}", applicationId, adminId);
		return applicationMapper.toResponseDto(saved);
	}

	@Override
	@Transactional
	public StudioApplicationResponseDto rejectApplication(UUID applicationId, UUID adminId, String reason) {
		StudioApplication application = pendingApplicationForUpdate(applicationId);
		String normalizedReason = normalizeRequired(reason, "reason");
		if (normalizedReason.length() > 500) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR, "reason can be at most 500 characters");
		}
		application.setStatus(ApplicationStatus.REJECTED);
		application.setDecisionDate(LocalDateTime.now());
		application.setReviewedBy(resolveAdmin(adminId));
		application.setRejectionReason(normalizedReason);
		StudioApplication saved = applicationRepository.save(application);
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

	private User resolveAdmin(UUID adminId) {
		if (adminId == null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
		return userEntityFinder.getUser(adminId);
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

	private String normalizeBusinessName(String value, String fieldName) {
		String normalized = UsernameUtils.normalize(
				normalizeRequired(value, fieldName));
		if (normalized == null || normalized.isBlank()) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					fieldName + " is required");
		}
		return normalized;
	}
}
