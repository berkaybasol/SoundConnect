package com.berkayb.soundconnect.modules.application.venueapplication.service;


import com.berkayb.soundconnect.modules.application.venueapplication.dto.request.VenueApplicationCreateRequestDto;
import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.mapper.VenueApplicationMapper;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.location.entity.City;
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
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
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
	
	@Transactional // islemlerden biri bile basarisiz olursa butun islemler geri alinir.
	@Override
	public VenueApplicationResponseDto approveApplication(UUID applicationId, UUID adminId) {
		VenueApplication application = venueApplicationRepository.findById(applicationId)
		                                                         .orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_APPLICATION_NOT_FOUND));
		if (application.getStatus() != ApplicationStatus.PENDING) {
			throw new SoundConnectException(ErrorType.INVALID_APPLICATION_STATUS);
		}
		
		// basvuru sahibi
		User applicant = application.getApplicant();
		
		// venue rolu atanacak
		Role venueRole = roleRepository.findByName(RoleEnum.ROLE_VENUE.name())
		                               .orElseThrow(() -> new SoundConnectException(ErrorType.ROLE_NOT_FOUND));
		
		// rol zaten var ise tekrar atama
		if (applicant.getRoles().stream().noneMatch(role -> role.getName().equals(RoleEnum.ROLE_VENUE.name()))) {
			applicant.getRoles().add(venueRole);
		}
		applicant.setStatus(UserStatus.ACTIVE);
		userRepository.save(applicant);
		
		City cityEntity = locationEntityFinder.getCityByName(applicant.getCity().name());
		Venue venue = Venue.builder()
		                   .name(application.getVenueName())
		                   .address(application.getVenueAddress())
		                   .city(cityEntity)
		                   .district(null)
		                   .neighborhood(null)
		                   .owner(applicant)
		                   .phone(application.getPhone())
		                   .status(VenueStatus.APPROVED)
		                   .build();
		venueRepository.save(venue);

// VenueProfile olustur
		venueProfileService.createProfile(venue.getId(), new VenueProfileSaveRequestDto(
				null, null, null,
				null, null
		));

// Application'ı güncelle
		application.setStatus(ApplicationStatus.APPROVED);
		application.setDecisionDate(LocalDateTime.now());
		venueApplicationRepository.save(application);
		
		log.info("Venue application {} approved by admin {}. Venue {} created, role assigned.", applicationId, adminId, venue.getName());
		
		return venueApplicationMapper.toResponseDto(application);
		
	}
	
	
	@Transactional // islemlerden biri bile basarisiz olursa butun islemler geri alinir.
	@Override
	public VenueApplicationResponseDto rejectApplication(UUID applicationId, UUID adminId, String reason) {
		VenueApplication application = venueApplicationRepository.findById(applicationId)
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
	
	@Override
	public VenueApplicationResponseDto createApplication(UUID applicantUserId, VenueApplicationCreateRequestDto dto) {
		// kullanici var mi kontrol et
		User applicant = userEntityFinder.getUser(applicantUserId);
		
		
		// zaten basvurmus mu?
		venueApplicationRepository.findByApplicantAndStatus(applicant, ApplicationStatus.PENDING)
				.ifPresent(existing -> {
					log.warn("User {} already has a pending venue application (id: {})", applicant.getUsername(), existing.getId());
					throw new SoundConnectException(ErrorType.VENUE_APPLICATION_ALREADY_EXISTS);
				});
		
		
		// dto -> entity mapping
		VenueApplication application = venueApplicationMapper.toEntity(dto);
		application.setApplicant(applicant);
		application.setStatus(ApplicationStatus.PENDING);
		application.setApplicationDate(LocalDateTime.now());
		application.setDecisionDate(null); // henuz karar yok biz onaylicaz
		
		VenueApplication saved = venueApplicationRepository.save(application);
		log.info("Venue application created for user {}. Application id: {}", applicant.getUsername(), saved.getId());
		
		return venueApplicationMapper.toResponseDto(saved);
		
	}
	
	@Override
	public List<VenueApplicationResponseDto> getApplicationsByUser(UUID applicantUserId) {
		User applicant = userEntityFinder.getUser(applicantUserId);
		List<VenueApplication> apps = venueApplicationRepository.findAllByApplicant(applicant);
		return apps.stream().map(venueApplicationMapper :: toResponseDto).toList();
	}
	
	@Override
	public List<VenueApplicationResponseDto> getApplicationsByStatus(ApplicationStatus status) {
		List<VenueApplication> apps = venueApplicationRepository.findAllByStatus(status);
		return apps.stream().map(venueApplicationMapper :: toResponseDto).toList();
	}
	
	@Override
	public VenueApplicationResponseDto getPendingApplicationByUser(UUID applicantUserId) {
		User applicant = userEntityFinder.getUser(applicantUserId);
		VenueApplication pending = venueApplicationRepository
				.findByApplicantAndStatus(applicant, ApplicationStatus.PENDING)
				.orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_APPLICATION_NOT_FOUND));
		return venueApplicationMapper.toResponseDto(pending);
	}
	
	@Override
	public VenueApplicationResponseDto getById(UUID applicationId) {
		VenueApplication application = venueApplicationRepository.findById(applicationId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.VENUE_APPLICATION_NOT_FOUND));
		return venueApplicationMapper.toResponseDto(application);
	}
}