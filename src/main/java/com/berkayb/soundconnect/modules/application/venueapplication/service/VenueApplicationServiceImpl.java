package com.berkayb.soundconnect.modules.application.venueapplication.service;


import com.berkayb.soundconnect.modules.application.venueapplication.dto.request.VenueApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.mapper.VenueApplicationMapper;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
import com.berkayb.soundconnect.modules.location.entity.District;
import com.berkayb.soundconnect.modules.location.entity.Neighborhood;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.request.VenueProfileSaveRequestDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.enums.VenueStatus;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;

import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.util.UsernameUtils;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class VenueApplicationServiceImpl implements VenueApplicationService {
	
	private final VenueApplicationRepository venueApplicationRepository;
	private final UserEntityFinder userEntityFinder;
	private final LocationEntityFinder locationEntityFinder;
	private final VenueApplicationMapper venueApplicationMapper;
	private final RoleRepository roleRepository;
	private final UserRepository userRepository;
	private final VenueRepository venueRepository;
	private final VenueProfileService venueProfileService;
	private final VenueApplicationAdminMailService venueApplicationAdminMailService;
	
	@Transactional // islemlerden biri bile basarisiz olursa butun islemler geri alinir.
	@Override
	public VenueApplicationResponseDto approveApplication(UUID applicationId, UUID adminId) {
		VenueApplication application = venueApplicationRepository.findByIdForUpdate(applicationId)
		                                                         .orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_APPLICATION_NOT_FOUND));
		if (application.getStatus() != ApplicationStatus.PENDING) {
			throw new SoundConnectException(ErrorType.INVALID_APPLICATION_STATUS);
		}
		
		// basvuru sahibi
		User applicant = application.getApplicant();
		
		// venue rolu atanacak
		Role venueRole = roleRepository.findByName(RoleEnum.ROLE_VENUE.name())
		                               .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
		
		// rol zaten varsa tekrar atama
		Set<Role> roles = new HashSet<>(applicant.getRoles());
		
		boolean hasVenueRole = roles.stream()
				.anyMatch(role -> role.getName().equals(RoleEnum.ROLE_VENUE.name()));
		
		if(!hasVenueRole)
		{
			roles.add(venueRole);
		}
		
		applicant.setRoles(roles);
		applicant.setStatus(UserStatus.ACTIVE);
		userRepository.save(applicant);
		
		Venue venue = Venue.builder()
		                   .name(application.getVenueName())
		                   .address(application.getVenueAddress())
		                   .city(application.getCity())
		                   .district(application.getDistrict())
		                   .neighborhood(application.getNeighborhood())
		                   .owner(applicant)
		                   .phone(application.getPhone())
		                   .status(VenueStatus.APPROVED)
		                   .build();
		venueRepository.save(venue);
		
		venueProfileService.createProfile(venue.getId(), new VenueProfileSaveRequestDto(null, null, null, null, null));
		
		application.setStatus(ApplicationStatus.APPROVED);
		application.setDecisionDate(LocalDateTime.now());
		venueApplicationRepository.save(application);
		
		return venueApplicationMapper.toResponseDto(application);
	}
	
	
	@Transactional // islemlerden biri bile basarisiz olursa butun islemler geri alinir.
	@Override
	public VenueApplicationResponseDto rejectApplication(UUID applicationId, UUID adminId, String reason) {
		VenueApplication application = venueApplicationRepository.findByIdForUpdate(applicationId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_APPLICATION_NOT_FOUND));
		if (application.getStatus() != ApplicationStatus.PENDING) {
			throw new SoundConnectException(ErrorType.INVALID_APPLICATION_STATUS);
		}
		application.setStatus(ApplicationStatus.REJECTED);
		application.setDecisionDate(LocalDateTime.now());
		venueApplicationRepository.save(application);
		
		log.info("Venue application {} rejected by admin {}. Reason {}", applicationId, adminId, reason);
		//TODO notification ve mail module..
		return venueApplicationMapper.toResponseDto(application);
	}
	
	@Transactional
	@Override
	public VenueApplicationResponseDto createApplication(UUID applicantUserId, VenueApplicationCreateRequestDto dto) {
		// Applicant row is the serialization point for the "one pending application"
		// invariant. Concurrent submissions by the same account cannot both pass the
		// pending lookup and insert a new row.
		User applicant = userRepository.findByIdForUpdate(applicantUserId)
		                               .orElseThrow(() -> new SoundConnectException(ErrorType.USER_NOT_FOUND));
		
		
		// zaten basvurmus mu?
		venueApplicationRepository.findByApplicantAndStatus(applicant, ApplicationStatus.PENDING)
				.ifPresent(existing -> {
					log.warn("User {} already has a pending venue application (id: {})", applicant.getUsername(), existing.getId());
					throw new SoundConnectException(ErrorType.VENUE_APPLICATION_ALREADY_EXISTS);
				});
		
		
		// Venue tablosunda neighborhood zorunludur. Web validation'i atlayan
		// dahili cagrilar da ayni kontrata tabi olsun.
		City city = locationEntityFinder.getCity(parseRequiredLocationId(dto.cityId(), "cityId"));
		District district = locationEntityFinder.getDistrict(parseRequiredLocationId(dto.districtId(), "districtId"));
		Neighborhood neighborhood = locationEntityFinder.getNeighborhood(
				parseRequiredLocationId(dto.neighborhoodId(), "neighborhoodId")
		);
		if (!district.getCity().getId().equals(city.getId())) {
			throw new SoundConnectException(ErrorType.DISTRICT_CITY_MISMATCH);
		}
		if (!neighborhood.getDistrict().getId().equals(district.getId())) {
			throw new SoundConnectException(ErrorType.NEIGHBORHOOD_DISTRICT_MISMATCH);
		}
		// dto -> entity mapping
		VenueApplication application = venueApplicationMapper.toEntity(dto);
		application.setApplicant(applicant);
		application.setVenueName(normalizeBusinessName(dto.venueName(), "venueName"));
		application.setPhone(dto.phone());
		application.setCity(city);
		application.setDistrict(district);
		application.setNeighborhood(neighborhood);
		application.setStatus(ApplicationStatus.PENDING);
		application.setApplicationDate(LocalDateTime.now());
		application.setDecisionDate(null); // henuz karar yok biz onaylicaz
		
		VenueApplication saved = venueApplicationRepository.save(application);
		log.info("Venue application created for user {}. Application id: {}", applicant.getUsername(), saved.getId());
		venueApplicationAdminMailService.sendNewApplicationMail(saved);
		
		return venueApplicationMapper.toResponseDto(saved);
		
	}
	
	@Transactional(readOnly = true)
	@Override
	public List<VenueApplicationResponseDto> getApplicationsByUser(UUID applicantUserId) {
		User applicant = userEntityFinder.getUser(applicantUserId);
		List<VenueApplication> apps = venueApplicationRepository.findAllByApplicant(applicant);
		return apps.stream().map(venueApplicationMapper :: toResponseDto).toList();
	}
	
	@Transactional(readOnly = true)
	@Override
	public List<VenueApplicationResponseDto> getApplicationsByStatus(ApplicationStatus status) {
		List<VenueApplication> apps = venueApplicationRepository.findAllByStatus(status);
		return apps.stream().map(venueApplicationMapper :: toResponseDto).toList();
	}
	
	@Transactional(readOnly = true)
	@Override
	public VenueApplicationResponseDto getPendingApplicationByUser(UUID applicantUserId) {
		User applicant = userEntityFinder.getUser(applicantUserId);
		VenueApplication pending = venueApplicationRepository
				.findByApplicantAndStatus(applicant, ApplicationStatus.PENDING)
				.orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_APPLICATION_NOT_FOUND));
		return venueApplicationMapper.toResponseDto(pending);
	}
	
	@Transactional(readOnly = true)
	@Override
	public VenueApplicationResponseDto getById(UUID applicationId) {
		VenueApplication application = venueApplicationRepository.findById(applicationId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_APPLICATION_NOT_FOUND));
		return venueApplicationMapper.toResponseDto(application);
	}

	private UUID parseRequiredLocationId(String rawId, String fieldName) {
		if (rawId == null || rawId.isBlank()) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR, fieldName + " is required");
		}
		try {
			return UUID.fromString(rawId);
		} catch (IllegalArgumentException exception) {
			throw new SoundConnectException(ErrorType.VALIDATION_ERROR, fieldName + " must be a valid UUID");
		}
	}

	private String normalizeBusinessName(String value, String fieldName) {
		String normalized = UsernameUtils.normalize(value);
		if (normalized == null || normalized.isBlank()) {
			throw new SoundConnectException(
					ErrorType.VALIDATION_ERROR,
					fieldName + " is required");
		}
		return normalized;
	}
}
