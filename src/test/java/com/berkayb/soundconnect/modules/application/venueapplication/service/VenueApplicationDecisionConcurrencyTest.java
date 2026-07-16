package com.berkayb.soundconnect.modules.application.venueapplication.service;

import com.berkayb.soundconnect.modules.application.venueapplication.dto.response.VenueApplicationResponseDto;
import com.berkayb.soundconnect.modules.application.venueapplication.entity.VenueApplication;
import com.berkayb.soundconnect.modules.application.venueapplication.enums.ApplicationStatus;
import com.berkayb.soundconnect.modules.application.venueapplication.mapper.VenueApplicationMapper;
import com.berkayb.soundconnect.modules.application.venueapplication.repository.VenueApplicationRepository;
import com.berkayb.soundconnect.modules.location.support.LocationEntityFinder;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueProfileService;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.modules.venue.entity.Venue;
import com.berkayb.soundconnect.modules.venue.repository.VenueRepository;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Lock;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VenueApplicationDecisionConcurrencyTest {

	@Mock VenueApplicationRepository venueApplicationRepository;
	@Mock UserEntityFinder userEntityFinder;
	@Mock LocationEntityFinder locationEntityFinder;
	@Mock VenueApplicationMapper venueApplicationMapper;
	@Mock RoleRepository roleRepository;
	@Mock UserRepository userRepository;
	@Mock VenueRepository venueRepository;
	@Mock VenueProfileService venueProfileService;
	@Mock VenueApplicationAdminMailService venueApplicationAdminMailService;
	@InjectMocks VenueApplicationServiceImpl service;

	@Test
	void decisionFinderDeclaresPessimisticWriteLock() throws NoSuchMethodException {
		Lock lock = VenueApplicationRepository.class
				.getMethod("findByIdForUpdate", UUID.class)
				.getAnnotation(Lock.class);

		assertThat(lock).isNotNull();
		assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
	}

	@Test
	void rejectDecisionLoadsApplicationThroughWriteLock() {
		UUID applicationId = UUID.randomUUID();
		VenueApplication application = pendingApplication(applicationId);
		VenueApplicationResponseDto response = response(applicationId, ApplicationStatus.REJECTED);
		when(venueApplicationRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(application));
		when(venueApplicationMapper.toResponseDto(application)).thenReturn(response);

		VenueApplicationResponseDto result = service.rejectApplication(
				applicationId,
				UUID.randomUUID(),
				"insufficient details"
		);

		assertThat(result).isSameAs(response);
		assertThat(application.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
		assertThat(application.getDecisionDate()).isNotNull();
		verify(venueApplicationRepository).findByIdForUpdate(applicationId);
		verify(venueApplicationRepository, never()).findById(applicationId);
	}

	@Test
	void approveDecisionLoadsApplicationThroughWriteLockBeforeCreatingVenue() {
		UUID applicationId = UUID.randomUUID();
		VenueApplication application = pendingApplication(applicationId);
		Role venueRole = Role.builder().name(RoleEnum.ROLE_VENUE.name()).build();
		VenueApplicationResponseDto response = response(applicationId, ApplicationStatus.APPROVED);
		when(venueApplicationRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(application));
		when(roleRepository.findByName(RoleEnum.ROLE_VENUE.name())).thenReturn(Optional.of(venueRole));
		when(venueRepository.save(any(Venue.class))).thenAnswer(invocation -> invocation.getArgument(0));
		when(venueApplicationMapper.toResponseDto(application)).thenReturn(response);

		VenueApplicationResponseDto result = service.approveApplication(applicationId, UUID.randomUUID());

		assertThat(result).isSameAs(response);
		assertThat(application.getStatus()).isEqualTo(ApplicationStatus.APPROVED);
		assertThat(application.getApplicant().getRoles()).contains(venueRole);
		verify(venueApplicationRepository).findByIdForUpdate(applicationId);
		verify(venueApplicationRepository, never()).findById(applicationId);
		verify(venueRepository).save(any(Venue.class));
	}

	private VenueApplication pendingApplication(UUID applicationId) {
		User applicant = User.builder()
		                     .id(UUID.randomUUID())
		                     .username("venue-applicant")
		                     .roles(new HashSet<>())
		                     .build();
		return VenueApplication.builder()
		                       .id(applicationId)
		                       .applicant(applicant)
		                       .venueName("Venue")
		                       .venueAddress("Address")
		                       .status(ApplicationStatus.PENDING)
		                       .applicationDate(LocalDateTime.now())
		                       .build();
	}

	private VenueApplicationResponseDto response(UUID applicationId, ApplicationStatus status) {
		return new VenueApplicationResponseDto(
				applicationId,
				"venue-applicant",
				"Venue",
				"Address",
				null,
				status,
				LocalDateTime.now(),
				LocalDateTime.now()
		);
	}
}
