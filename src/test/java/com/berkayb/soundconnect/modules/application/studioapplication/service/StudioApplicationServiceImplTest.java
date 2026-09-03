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
import com.berkayb.soundconnect.modules.profile.shared.type.PersonalProfileTypePolicy;
import com.berkayb.soundconnect.modules.role.entity.Role;
import com.berkayb.soundconnect.modules.role.enums.RoleEnum;
import com.berkayb.soundconnect.modules.role.repository.RoleRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.modules.user.support.UserEntityFinder;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StudioApplicationServiceImplTest {
	@Mock StudioApplicationRepository applicationRepository;
	@Mock StudioApplicationMapper applicationMapper;
	@Mock UserRepository userRepository;
	@Mock UserEntityFinder userEntityFinder;
	@Mock LocationEntityFinder locationEntityFinder;
	@Mock RoleRepository roleRepository;
	@Mock StudioProfileRepository studioProfileRepository;
	@Mock StudioProfileService studioProfileService;
	@Mock StudioApplicationAdminMailService studioApplicationAdminMailService;
	@Mock StudioApplicationTimeProvider timeProvider;
	@Mock PersonalProfileTypePolicy personalProfileTypePolicy;
	@InjectMocks StudioApplicationServiceImpl service;

	private UUID applicantId;
	private UUID adminId;
	private City city;
	private District district;
	private Neighborhood neighborhood;
	private User applicant;
	private LocalDateTime now;

	@BeforeEach
	void setUp() {
		applicantId = UUID.randomUUID();
		adminId = UUID.randomUUID();
		city = City.builder().id(UUID.randomUUID()).name("Istanbul").build();
		district = District.builder().id(UUID.randomUUID()).name("Kadikoy").city(city).build();
		neighborhood = Neighborhood.builder().id(UUID.randomUUID()).name("Moda").district(district).build();
		applicant = User.builder()
				.id(applicantId)
				.username("studio-owner")
				.email("studio@example.com")
				.password("encoded")
				.emailVerified(true)
				.status(UserStatus.PENDING_STUDIO_REQUEST)
				.roles(new HashSet<>())
				.build();
		now = LocalDateTime.of(2026, 8, 3, 9, 15);
		lenient().when(timeProvider.nowUtc()).thenReturn(now);
		lenient().when(personalProfileTypePolicy.lockAndAssertCanAcquire(
				applicantId, RoleEnum.ROLE_STUDIO)).thenReturn(applicant);
	}

	@Test
	void createApplicationPersistsValidatedLocationSnapshot() {
		StudioApplicationCreateRequestDto request = request(neighborhood.getId());
		when(applicationRepository.findByApplicantAndStatus(applicant, ApplicationStatus.PENDING))
				.thenReturn(Optional.empty());
		stubLocationLookup();
		when(applicationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		StudioApplicationResponseDto expected = response(ApplicationStatus.PENDING);
		when(applicationMapper.toResponseDto(any())).thenReturn(expected);

		StudioApplicationResponseDto result = service.createApplication(applicantId, request);

		assertThat(result).isSameAs(expected);
		ArgumentCaptor<StudioApplication> captor = ArgumentCaptor.forClass(StudioApplication.class);
		verify(applicationRepository).save(captor.capture());
		StudioApplication saved = captor.getValue();
		assertThat(saved.getStudioName()).isEqualTo("devo studio");
		assertThat(saved.getPhone()).isEqualTo("05551234567");
		assertThat(saved.getCity()).isSameAs(city);
		assertThat(saved.getDistrict()).isSameAs(district);
		assertThat(saved.getNeighborhood()).isSameAs(neighborhood);
		assertThat(saved.getStatus()).isEqualTo(ApplicationStatus.PENDING);
		assertThat(saved.getApplicationDate()).isEqualTo(now);
		verify(personalProfileTypePolicy).lockAndAssertCanAcquire(
				applicantId, RoleEnum.ROLE_STUDIO);
		verify(studioApplicationAdminMailService).sendNewApplicationMail(saved);
	}

	@Test
	void createApplicationRejectsAnAccountThatAlreadyHasTheStudioRole() {
		applicant.getRoles().add(Role.builder()
				.id(UUID.randomUUID())
				.name(RoleEnum.ROLE_STUDIO.name())
				.build());
		assertThatThrownBy(() -> service.createApplication(applicantId, request(neighborhood.getId())))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.STUDIO_APPLICATION_ALREADY_EXISTS));

		verify(studioProfileRepository, never()).existsByUserId(any());
		verify(applicationRepository, never()).findByApplicantAndStatus(any(), any());
		verify(applicationRepository, never()).save(any());
	}

	@Test
	void createApplicationRejectsAnAccountThatAlreadyHasAStudioProfile() {
		when(studioProfileRepository.existsByUserId(applicantId)).thenReturn(true);

		assertThatThrownBy(() -> service.createApplication(applicantId, request(neighborhood.getId())))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.STUDIO_APPLICATION_ALREADY_EXISTS));

		verify(applicationRepository, never()).findByApplicantAndStatus(any(), any());
		verify(applicationRepository, never()).save(any());
	}

	@Test
	void createApplicationRejectsDatabaseOverflowBeforeLocationLookupOrInsert() {
		when(applicationRepository.findByApplicantAndStatus(applicant, ApplicationStatus.PENDING))
				.thenReturn(Optional.empty());

		assertValidationError(new StudioApplicationCreateRequestDto(
				"s".repeat(101), "Moda Caddesi", "05551234567",
				city.getId().toString(), district.getId().toString(), neighborhood.getId().toString()
		));
		assertValidationError(new StudioApplicationCreateRequestDto(
				"Devo Studio", "a".repeat(256), "05551234567",
				city.getId().toString(), district.getId().toString(), neighborhood.getId().toString()
		));

		verify(locationEntityFinder, never()).getCity(any());
		verify(applicationRepository, never()).save(any());
	}

	@Test
	void createApplicationRejectsMalformedPhoneBeforeInsert() {
		when(applicationRepository.findByApplicantAndStatus(applicant, ApplicationStatus.PENDING))
				.thenReturn(Optional.empty());

		assertValidationError(new StudioApplicationCreateRequestDto(
				"Devo Studio", "Moda Caddesi", "0555-call-me",
				city.getId().toString(), district.getId().toString(), neighborhood.getId().toString()
		));

		verify(locationEntityFinder, never()).getCity(any());
		verify(applicationRepository, never()).save(any());
	}

	@Test
	void createApplicationRejectsMismatchedNeighborhoodBeforeInsert() {
		District otherDistrict = District.builder().id(UUID.randomUUID()).city(city).build();
		Neighborhood otherNeighborhood = Neighborhood.builder()
				.id(UUID.randomUUID()).district(otherDistrict).build();
		when(applicationRepository.findByApplicantAndStatus(applicant, ApplicationStatus.PENDING))
				.thenReturn(Optional.empty());
		when(locationEntityFinder.getCity(city.getId())).thenReturn(city);
		when(locationEntityFinder.getDistrict(district.getId())).thenReturn(district);
		when(locationEntityFinder.getNeighborhood(otherNeighborhood.getId())).thenReturn(otherNeighborhood);

		assertThatThrownBy(() -> service.createApplication(applicantId, request(otherNeighborhood.getId())))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.NEIGHBORHOOD_DISTRICT_MISMATCH));
		verify(applicationRepository, never()).save(any());
	}

	@Test
	void adminApplicationsUseBoundedStableStatusDateIdPagination() {
		StudioApplication application = StudioApplication.builder()
				.id(UUID.randomUUID())
				.status(ApplicationStatus.PENDING)
				.applicationDate(LocalDateTime.now())
				.build();
		StudioApplicationResponseDto mapped = response(ApplicationStatus.PENDING);
		when(applicationRepository.findAllByStatus(eq(ApplicationStatus.PENDING), any(Pageable.class)))
				.thenAnswer(invocation -> new PageImpl<>(
						List.of(application), invocation.getArgument(1), 51));
		when(applicationMapper.toResponseDto(application)).thenReturn(mapped);

		var result = service.getApplicationsByStatus(ApplicationStatus.PENDING, 2, 25);

		assertThat(result.content()).containsExactly(mapped);
		assertThat(result.page()).isEqualTo(2);
		assertThat(result.size()).isEqualTo(25);
		assertThat(result.totalElements()).isEqualTo(51);
		ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
		verify(applicationRepository).findAllByStatus(eq(ApplicationStatus.PENDING), pageable.capture());
		assertThat(pageable.getValue().getSort().getOrderFor("applicationDate"))
				.extracting(Sort.Order::getDirection)
				.isEqualTo(Sort.Direction.ASC);
		assertThat(pageable.getValue().getSort().getOrderFor("id"))
				.extracting(Sort.Order::getDirection)
				.isEqualTo(Sort.Direction.ASC);
	}

	@Test
	void applicationPaginationRejectsAnUnboundedPageSize() {
		assertThatThrownBy(() -> service.getApplicationsByStatus(
				ApplicationStatus.PENDING, 0, 101))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.VALIDATION_ERROR));
		verify(applicationRepository, never()).findAllByStatus(any(), any(Pageable.class));
	}

	@Test
	void applicationPaginationRejectsADeepOffsetBeforeRepositoryAccess() {
		assertThatThrownBy(() -> service.getApplicationsByStatus(
				ApplicationStatus.PENDING, 1001, 20))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.VALIDATION_ERROR));
		verify(applicationRepository, never()).findAllByStatus(any(), any(Pageable.class));
	}

	@Test
	void approveApplicationAssignsRoleAndProvisionsLocationBoundProfileAtomically() {
		UUID applicationId = UUID.randomUUID();
		User admin = User.builder().id(adminId).username("admin").build();
		Role studioRole = Role.builder().id(UUID.randomUUID()).name(RoleEnum.ROLE_STUDIO.name()).build();
		StudioApplication application = StudioApplication.builder()
				.id(applicationId)
				.applicant(applicant)
				.studioName("Devo Studio")
				.studioAddress("Moda Caddesi")
				.phone("05551234567")
				.city(city)
				.district(district)
				.neighborhood(neighborhood)
				.status(ApplicationStatus.PENDING)
				.applicationDate(LocalDateTime.now())
				.build();
		when(applicationRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(application));
		when(personalProfileTypePolicy.lockAndAssertCanAcquire(
				applicantId, RoleEnum.ROLE_STUDIO)).thenReturn(applicant);
		when(roleRepository.findByName(RoleEnum.ROLE_STUDIO.name())).thenReturn(Optional.of(studioRole));
		when(userEntityFinder.getUser(adminId)).thenReturn(admin);
		when(applicationRepository.save(application)).thenReturn(application);
		when(applicationMapper.toResponseDto(application)).thenReturn(response(ApplicationStatus.APPROVED));

		StudioApplicationResponseDto result = service.approveApplication(applicationId, adminId);

		assertThat(result.status()).isEqualTo(ApplicationStatus.APPROVED);
		assertThat(applicant.getStatus()).isEqualTo(UserStatus.ACTIVE);
		assertThat(applicant.getRoles()).contains(studioRole);
		assertThat(application.getReviewedBy()).isSameAs(admin);
		assertThat(application.getDecisionDate()).isNotNull();
		assertThat(application.getDecisionDate()).isEqualTo(now);
		verify(personalProfileTypePolicy).lockAndAssertCanAcquire(
				applicantId, RoleEnum.ROLE_STUDIO);
		verify(studioProfileRepository).existsByUserId(applicantId);
		ArgumentCaptor<StudioProfileProvisioningCommand> command =
				ArgumentCaptor.forClass(StudioProfileProvisioningCommand.class);
		verify(studioProfileService).createApprovedProfile(command.capture());
		assertThat(command.getValue().neighborhoodId()).isEqualTo(neighborhood.getId());
		verify(studioApplicationAdminMailService).sendApplicantDecisionMail(application);
	}

	@Test
	void createApplicationRejectsAConflictingPersonalProfileBeforeBusinessWrites() {
		when(personalProfileTypePolicy.lockAndAssertCanAcquire(
				applicantId, RoleEnum.ROLE_STUDIO))
				.thenThrow(new SoundConnectException(ErrorType.PROFILE_TYPE_IMMUTABLE));

		assertThatThrownBy(() -> service.createApplication(applicantId, request(neighborhood.getId())))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.PROFILE_TYPE_IMMUTABLE));

		verify(applicationRepository, never()).findByApplicantAndStatus(any(), any());
		verify(applicationRepository, never()).save(any());
		verify(studioProfileService, never()).createApprovedProfile(any());
	}

	@Test
	void approveApplicationDoesNotProvisionASecondStudioProfile() {
		UUID applicationId = UUID.randomUUID();
		StudioApplication application = StudioApplication.builder()
				.id(applicationId)
				.applicant(applicant)
				.status(ApplicationStatus.PENDING)
				.build();
		when(applicationRepository.findByIdForUpdate(applicationId))
				.thenReturn(Optional.of(application));
		when(studioProfileRepository.existsByUserId(applicantId)).thenReturn(true);

		assertThatThrownBy(() -> service.approveApplication(applicationId, adminId))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.STUDIO_APPLICATION_ALREADY_EXISTS));

		verify(roleRepository, never()).findByName(any());
		verify(studioProfileService, never()).createApprovedProfile(any());
		verify(applicationRepository, never()).save(any());
	}

	@Test
	void rejectApplicationClosesARegistrationAccountAndRecordsTheDecision() {
		UUID applicationId = UUID.randomUUID();
		User admin = User.builder().id(adminId).username("admin").build();
		StudioApplication application = StudioApplication.builder()
				.id(applicationId)
				.applicant(applicant)
				.status(ApplicationStatus.PENDING)
				.build();
		when(applicationRepository.findByIdForUpdate(applicationId))
				.thenReturn(Optional.of(application));
		when(userRepository.findByIdForUpdate(applicantId)).thenReturn(Optional.of(applicant));
		when(userEntityFinder.getUser(adminId)).thenReturn(admin);
		when(applicationRepository.save(application)).thenReturn(application);
		when(applicationMapper.toResponseDto(application))
				.thenReturn(response(ApplicationStatus.REJECTED));

		StudioApplicationResponseDto result = service.rejectApplication(
				applicationId, adminId, "  Belgeler doğrulanamadı.  ");

		assertThat(result.status()).isEqualTo(ApplicationStatus.REJECTED);
		assertThat(applicant.getStatus()).isEqualTo(UserStatus.REJECTED_STUDIO_REQUEST);
		assertThat(application.getReviewedBy()).isSameAs(admin);
		assertThat(application.getRejectionReason()).isEqualTo("Belgeler doğrulanamadı.");
		verify(userRepository).save(applicant);
		verify(studioApplicationAdminMailService).sendApplicantDecisionMail(application);
	}

	@Test
	void rejectingAStudioApplicationDoesNotDisableAnExistingActiveAccount() {
		UUID applicationId = UUID.randomUUID();
		applicant.setStatus(UserStatus.ACTIVE);
		StudioApplication application = StudioApplication.builder()
				.id(applicationId)
				.applicant(applicant)
				.status(ApplicationStatus.PENDING)
				.build();
		when(applicationRepository.findByIdForUpdate(applicationId))
				.thenReturn(Optional.of(application));
		when(userRepository.findByIdForUpdate(applicantId)).thenReturn(Optional.of(applicant));
		when(userEntityFinder.getUser(adminId)).thenReturn(User.builder().id(adminId).build());
		when(applicationRepository.save(application)).thenReturn(application);
		when(applicationMapper.toResponseDto(application))
				.thenReturn(response(ApplicationStatus.REJECTED));

		service.rejectApplication(applicationId, adminId, "Uygun bulunmadı");

		assertThat(applicant.getStatus()).isEqualTo(UserStatus.ACTIVE);
		verify(userRepository, never()).save(applicant);
		verify(studioApplicationAdminMailService).sendApplicantDecisionMail(application);
	}

	@Test
	void secondDecisionIsRejectedWithoutSideEffects() {
		UUID applicationId = UUID.randomUUID();
		StudioApplication application = StudioApplication.builder()
				.id(applicationId).applicant(applicant).status(ApplicationStatus.APPROVED).build();
		when(applicationRepository.findByIdForUpdate(applicationId)).thenReturn(Optional.of(application));

		assertThatThrownBy(() -> service.approveApplication(applicationId, adminId))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.INVALID_APPLICATION_STATUS));
		verify(studioProfileService, never()).createApprovedProfile(any());
		verify(studioApplicationAdminMailService, never()).sendApplicantDecisionMail(any());
	}

	private StudioApplicationCreateRequestDto request(UUID neighborhoodId) {
		return new StudioApplicationCreateRequestDto(
				"devo studio", "Moda Caddesi", "555 123 45 67",
				city.getId().toString(), district.getId().toString(), neighborhoodId.toString()
		);
	}

	private void assertValidationError(StudioApplicationCreateRequestDto request) {
		assertThatThrownBy(() -> service.createApplication(applicantId, request))
				.isInstanceOfSatisfying(SoundConnectException.class,
						exception -> assertThat(exception.getErrorType())
								.isEqualTo(ErrorType.VALIDATION_ERROR));
	}

	private void stubLocationLookup() {
		when(locationEntityFinder.getCity(city.getId())).thenReturn(city);
		when(locationEntityFinder.getDistrict(district.getId())).thenReturn(district);
		when(locationEntityFinder.getNeighborhood(neighborhood.getId())).thenReturn(neighborhood);
	}

	private StudioApplicationResponseDto response(ApplicationStatus status) {
		return new StudioApplicationResponseDto(
				UUID.randomUUID(), applicantId, applicant.getUsername(), "Devo Studio", "Moda Caddesi",
				"05551234567", city.getId(), city.getName(), district.getId(), district.getName(),
				neighborhood.getId(), neighborhood.getName(), status,
				LocalDateTime.now().toInstant(ZoneOffset.UTC), null, null, null
		);
	}
}
