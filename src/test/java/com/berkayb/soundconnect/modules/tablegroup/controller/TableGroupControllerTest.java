package com.berkayb.soundconnect.modules.tablegroup.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupJoinRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupParticipantDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupVenueOptionDto;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TableGroupController icin standalone MockMvc unit test.
 */
@ExtendWith(MockitoExtension.class)
class TableGroupControllerTest {
	
	@Mock
	private TableGroupService tableGroupService;
	
	@InjectMocks
	private TableGroupController controller;
	
	private MockMvc mockMvc;
	private ObjectMapper objectMapper;
	
	private UUID userId;
	private String username;
	private UsernamePasswordAuthenticationToken authentication;
	
	@BeforeEach
	void setUp() {
		userId = UUID.randomUUID();
		username = "testuser";
		UserDetailsImpl userDetails = new UserDetailsImpl(User.builder()
				.id(userId)
				.username(username)
				.build());
		authentication = new UsernamePasswordAuthenticationToken(userDetails, null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);

		mockMvc = MockMvcBuilders.standaloneSetup(controller)
		                         .setCustomArgumentResolvers(
				                         new AuthenticationPrincipalArgumentResolver(),
				                         new PageableHandlerMethodArgumentResolver()
		                         )
		                         .build();
		
		objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}
	
	private Principal principal() {
		return authentication;
	}
	
	private TableGroupResponseDto sampleResponseDto(UUID tableGroupId) {
		// Koleksiyonlar MUTABLE
		List<String> genders = new ArrayList<>();
		genders.add("MALE");
		genders.add("FEMALE");
		genders.add("OTHER");
		
		Set<TableGroupParticipantDto> participants = new HashSet<>();
		participants.add(
				new TableGroupParticipantDto(
						userId,
						Instant.now(),
						ParticipantStatus.ACCEPTED,
						null,
						username,
						null
				)
		);
		
		return new TableGroupResponseDto(
				tableGroupId,
				userId,          // ownerId
				username,        // ownerUsername
				null,            // ownerProfileImageUrl
				null,            // venueId
				"My Venue",
				"Yeni insanlarla tanışmak istiyorum",
				3,
				genders,
				20,
				30,
				Instant.now(),
				Instant.now().plusSeconds(3600),
				Instant.now().plusSeconds(7200),
				TableGroupStatus.ACTIVE,
				participants,
				null,
				null,
				null
		);
	}
	
	/**
	 * Basit, tamamen mutable bir Page implementasyonu.
	 * Jackson ile kavga etmiyor.
	 */
	static class TestPage<T> implements Page<T> {
		private final List<T> content;
		
		TestPage(List<T> content) {
			// İçi mutable bir liste olsun
			this.content = new ArrayList<>(content);
		}
		
		@Override
		public int getTotalPages() {
			return 1;
		}
		
		@Override
		public long getTotalElements() {
			return content.size();
		}
		
		@Override
		public <U> Page<U> map(Function<? super T, ? extends U> converter) {
			List<U> mapped = content.stream()
			                        .map(converter)
			                        .collect(Collectors.toList());
			return new TestPage<>(mapped);
		}
		
		@Override
		public int getNumber() {
			return 0;
		}
		
		@Override
		public int getSize() {
			return content.size();
		}
		
		@Override
		public int getNumberOfElements() {
			return content.size();
		}
		
		@Override
		public List<T> getContent() {
			return content;
		}
		
		@Override
		public boolean hasContent() {
			return !content.isEmpty();
		}
		
		@Override
		public Sort getSort() {
			return Sort.unsorted();
		}
		
		@Override
		public boolean isFirst() {
			return true;
		}
		
		@Override
		public boolean isLast() {
			return true;
		}
		
		@Override
		public boolean hasNext() {
			return false;
		}
		
		@Override
		public boolean hasPrevious() {
			return false;
		}
		
		@Override
		public Pageable getPageable() {
			return Pageable.unpaged();
		}
		
		@Override
		public Pageable nextPageable() {
			return Pageable.unpaged();
		}
		
		@Override
		public Pageable previousPageable() {
			return Pageable.unpaged();
		}
		
		@Override
		public Iterator<T> iterator() {
			return content.iterator();
		}
	}
	
	// -------------------- createTableGroup --------------------

	@Test
	void createAndJoin_shouldDelegateInstitutionalBoundaryToServiceAndExposeNoActorField()
			throws Exception {
		PreAuthorize createPolicy = TableGroupController.class
				.getDeclaredMethod(
						"createTableGroup", UserDetailsImpl.class, TableGroupCreateRequestDto.class)
				.getAnnotation(PreAuthorize.class);
		PreAuthorize joinPolicy = TableGroupController.class
				.getDeclaredMethod(
						"joinTableGroup", UserDetailsImpl.class, UUID.class,
						TableGroupJoinRequestDto.class)
				.getAnnotation(PreAuthorize.class);

		assertThat(createPolicy).isNotNull();
		assertThat(joinPolicy).isNotNull();
		assertThat(createPolicy.value()).isEqualTo("isAuthenticated()");
		assertThat(joinPolicy.value()).isEqualTo(createPolicy.value());

		assertThat(Arrays.stream(TableGroupCreateRequestDto.class.getRecordComponents())
				.map(java.lang.reflect.RecordComponent::getName))
				.doesNotContain("ownerId", "userId", "bandId");
		assertThat(Arrays.stream(TableGroupJoinRequestDto.class.getRecordComponents())
				.map(java.lang.reflect.RecordComponent::getName))
				.doesNotContain("ownerId", "userId", "bandId");
	}

	@Test
	void findVenueOptions_shouldExposeAuthenticatedMinimalDeterministicOptions() throws Exception {
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		List<TableGroupVenueOptionDto> options = List.of(
				new TableGroupVenueOptionDto(
						firstId, "Sound Hub", "https://cdn.test/sound-hub.jpg", "Alpha address",
						UUID.randomUUID(), "Istanbul", UUID.randomUUID(), "Kadikoy",
						UUID.randomUUID(), "Caferaga"),
				new TableGroupVenueOptionDto(
						secondId, "Sound Hub", null, "Beta address",
						UUID.randomUUID(), "Ankara", UUID.randomUUID(), "Cankaya",
						UUID.randomUUID(), "Bahcelievler")
		);
		when(tableGroupService.findVenueOptions("Sound Hub", 8)).thenReturn(options);

		var mvcResult = mockMvc.perform(get(EndPoints.TableGroup.BASE + EndPoints.TableGroup.VENUE_OPTIONS)
						.principal(principal())
						.param("q", "Sound Hub"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.code").value(200))
				.andExpect(jsonPath("$.data[0].id").value(firstId.toString()))
				.andExpect(jsonPath("$.data[1].id").value(secondId.toString()))
				.andExpect(jsonPath("$.data[0].name").value("Sound Hub"))
				.andExpect(jsonPath("$.data[1].name").value("Sound Hub"))
				.andExpect(jsonPath("$.data[0].profilePictureUrl")
						.value("https://cdn.test/sound-hub.jpg"))
				.andExpect(jsonPath("$.data[0].cityName").value("Istanbul"))
				.andExpect(jsonPath("$.data[1].cityName").value("Ankara"))
				.andReturn();

		Set<String> optionFields = new HashSet<>();
		var responseData = objectMapper.readTree(mvcResult.getResponse().getContentAsString())
				.path("data");
		responseData.get(0).fieldNames().forEachRemaining(optionFields::add);
		assertThat(optionFields).containsExactlyInAnyOrder(
				"id", "name", "profilePictureUrl", "address", "cityId", "cityName",
				"districtId", "districtName", "neighborhoodId", "neighborhoodName"
		);
		assertThat(responseData.get(1).has("profilePictureUrl")).isTrue();
		assertThat(responseData.get(1).path("profilePictureUrl").isNull()).isTrue();
		PreAuthorize policy = TableGroupController.class
				.getDeclaredMethod("findVenueOptions", String.class, int.class)
				.getAnnotation(PreAuthorize.class);
		assertThat(policy).isNotNull();
		assertThat(policy.value()).isEqualTo("isAuthenticated()");
		verify(tableGroupService).findVenueOptions("Sound Hub", 8);
	}

	@Test
	void findVenueOptions_shouldRequireQueryParameterAtTheHttpBoundary() throws Exception {
		mockMvc.perform(get(EndPoints.TableGroup.BASE + EndPoints.TableGroup.VENUE_OPTIONS)
					.principal(principal()))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(tableGroupService);
	}
	
	@Test
	void createTableGroup_whenValid_shouldReturn201AndBaseResponseWithDto() throws Exception {
		// given
		UUID cityId = UUID.randomUUID();
		TableGroupCreateRequestDto requestDto = new TableGroupCreateRequestDto(
				null,
				"My Venue",
				"Yeni insanlarla tanışmak istiyorum",
				3,
				List.of("MALE", "FEMALE", "OTHER"),
				20,
				30,
				Instant.now().plusSeconds(7200),
				cityId,
				null,
				null
		);
		
		UUID tableGroupId = UUID.randomUUID();
		TableGroupResponseDto responseDto = sampleResponseDto(tableGroupId);
		
		when(tableGroupService.createTableGroup(eq(userId), any(TableGroupCreateRequestDto.class)))
				.thenReturn(responseDto);
		
		// when & then
		mockMvc.perform(
				       post(EndPoints.TableGroup.BASE)
						       .principal(principal())
						       .contentType("application/json")
						       .content(objectMapper.writeValueAsString(requestDto))
		       )
		       .andExpect(status().isCreated())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(201))
		       .andExpect(jsonPath("$.message").value("Masa olusturuldu"))
		       .andExpect(jsonPath("$.data.id").value(tableGroupId.toString()))
		       .andExpect(jsonPath("$.data.ownerId").value(userId.toString()))
		       .andExpect(jsonPath("$.data.description")
				       .value("Yeni insanlarla tanışmak istiyorum"));
		
		verify(tableGroupService).createTableGroup(eq(userId), any(TableGroupCreateRequestDto.class));
	}

	@Test
	void createTableGroup_whenDescriptionIsMissingOrBlank_shouldReturn400WithoutCallingService()
			throws Exception {
		for (String description : Arrays.asList(null, " \t ")) {
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("venueName", "Venue");
			if (description != null) {
				payload.put("description", description);
			}
			payload.put("maxPersonCount", 2);
			payload.put("genderPrefs", List.of("MALE", "FEMALE"));
			payload.put("ageMin", 20);
			payload.put("ageMax", 30);
			payload.put("meetingAt", Instant.now().plusSeconds(3600).toString());
			payload.put("cityId", UUID.randomUUID().toString());

			mockMvc.perform(post(EndPoints.TableGroup.BASE)
						.principal(principal())
						.contentType("application/json")
						.content(objectMapper.writeValueAsString(payload)))
					.andExpect(status().isBadRequest());
		}

		verifyNoInteractions(tableGroupService);
	}

	@Test
	void createTableGroup_whenRetiredExpiresAtAliasIsSent_shouldReturn400WithoutCallingService()
			throws Exception {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("venueName", "Venue");
		payload.put("description", "Masa açıklaması");
		payload.put("maxPersonCount", 2);
		payload.put("genderPrefs", List.of("MALE", "FEMALE"));
		payload.put("ageMin", 20);
		payload.put("ageMax", 30);
		payload.put("expiresAt", Instant.now().plusSeconds(3600).toString());
		payload.put("cityId", UUID.randomUUID().toString());

		mockMvc.perform(post(EndPoints.TableGroup.BASE)
					.principal(principal())
					.contentType("application/json")
					.content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(tableGroupService);
	}

	@Test
	void createTableGroup_whenVenueFieldsAreOmitted_shouldReachServiceWithNullVenue()
			throws Exception {
		when(tableGroupService.createTableGroup(eq(userId), any(TableGroupCreateRequestDto.class)))
				.thenReturn(sampleResponseDto(UUID.randomUUID()));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("description", "Mekanı daha sonra netleştireceğiz");
		payload.put("maxPersonCount", 2);
		payload.put("genderPrefs", List.of("MALE", "FEMALE"));
		payload.put("ageMin", 20);
		payload.put("ageMax", 30);
		payload.put("meetingAt", Instant.now().plusSeconds(3600).toString());
		payload.put("cityId", UUID.randomUUID().toString());

		mockMvc.perform(post(EndPoints.TableGroup.BASE)
					.principal(principal())
					.contentType("application/json")
					.content(objectMapper.writeValueAsString(payload)))
				.andExpect(status().isCreated());

		ArgumentCaptor<TableGroupCreateRequestDto> requestCaptor =
				ArgumentCaptor.forClass(TableGroupCreateRequestDto.class);
		verify(tableGroupService).createTableGroup(eq(userId), requestCaptor.capture());
		assertThat(requestCaptor.getValue().venueId()).isNull();
		assertThat(requestCaptor.getValue().venueName()).isNull();
	}

	@Test
	void createTableGroup_whenGenderPreferenceContainsNull_shouldReturn400WithoutCallingService() throws Exception {
		String json = """
				{
				  "venueName":"Venue", "description":"Masa açıklaması", "maxPersonCount":2,
				  "genderPrefs":["MALE",null], "ageMin":20, "ageMax":30,
				  "meetingAt":"2026-08-18T10:00:00Z",
				  "cityId":"00000000-0000-0000-0000-000000000001"
				}
				""";

		mockMvc.perform(post(EndPoints.TableGroup.BASE)
						.principal(principal())
						.contentType("application/json")
						.content(json))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(tableGroupService);
	}
	
	// -------------------- listActiveTableGroups --------------------
	

	
	@Test
	void listActiveTableGroups_whenValidCity_shouldReturnPageWrappedInBaseResponse() {
		// given
		UUID cityId = UUID.randomUUID();
		UUID tgId = UUID.randomUUID();
		
		TableGroupResponseDto dto = sampleResponseDto(tgId);
		
		// normal mutable liste + PageImpl
		List<TableGroupResponseDto> content = new ArrayList<>();
		content.add(dto);
		Page<TableGroupResponseDto> page = new PageImpl<>(content);
		
		when(tableGroupService.listActiveTableGroups(eq(userId), eq(cityId), isNull(), isNull(), any(Pageable.class)))
				.thenReturn(page);
		
		Pageable pageable = Pageable.unpaged();
		
		// when: MockMvc ile degil, controller metodunu direkt cagiriyoruz
		ResponseEntity<BaseResponse<Page<TableGroupResponseDto>>> response =
				controller.listActiveTableGroups((UserDetailsImpl) authentication.getPrincipal(), cityId, null, null, pageable);
		
		// then
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		
		BaseResponse<Page<TableGroupResponseDto>> body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.getSuccess()).isTrue();
		assertThat(body.getCode()).isEqualTo(200);
		assertThat(body.getMessage()).isEqualTo("Aktif masalar listelendi");
		assertThat(body.getData().getContent())
				.hasSize(1)
				.first()
				.extracting(TableGroupResponseDto::id)
				.isEqualTo(tgId);
		
		verify(tableGroupService).listActiveTableGroups(eq(userId), eq(cityId), isNull(), isNull(), any(Pageable.class));
	}

	@Test
	void listActiveTableGroups_whenCityIsOmitted_shouldRequestGlobalFeed() throws Exception {
		when(tableGroupService.listActiveTableGroups(
				eq(userId), isNull(), isNull(), isNull(), any(Pageable.class)))
				.thenReturn(new PageImpl<>(
						new ArrayList<>(),
						org.springframework.data.domain.PageRequest.of(0, 20),
						0
				));

		mockMvc.perform(get(EndPoints.TableGroup.BASE + EndPoints.TableGroup.LIST_ACTIVE)
					.principal(principal()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.content").isArray())
				.andExpect(jsonPath("$.data.content").isEmpty());

		verify(tableGroupService).listActiveTableGroups(
				eq(userId), isNull(), isNull(), isNull(), any(Pageable.class));
	}

	@Test
	void listMyActiveTableGroups_shouldUseAuthenticatedPrincipalAndLiteralRoute() throws Exception {
		when(tableGroupService.listMyActiveTableGroups(eq(userId), any(Pageable.class)))
				.thenReturn(new PageImpl<>(
						new ArrayList<>(),
						org.springframework.data.domain.PageRequest.of(0, 20),
						0
				));

		mockMvc.perform(get(EndPoints.TableGroup.BASE + EndPoints.TableGroup.LIST_MINE)
					.principal(principal()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.content").isArray())
				.andExpect(jsonPath("$.data.content").isEmpty());

		verify(tableGroupService).listMyActiveTableGroups(eq(userId), any(Pageable.class));
	}
	
	
	// -------------------- getTableGroupDetail --------------------
	
	@Test
	void getTableGroupDetail_whenExists_shouldReturnDtoInBaseResponse() throws Exception {
		// given
		UUID tableGroupId = UUID.randomUUID();
		TableGroupResponseDto dto = sampleResponseDto(tableGroupId);
		
		when(tableGroupService.getTableGroupDetail(userId, tableGroupId))
				.thenReturn(dto);
		
		// when & then
		mockMvc.perform(
				       get(EndPoints.TableGroup.BASE + EndPoints.TableGroup.DETAIL, tableGroupId)
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.data.id").value(tableGroupId.toString()))
		       .andExpect(jsonPath("$.data.ownerId").value(userId.toString()));
		
		verify(tableGroupService).getTableGroupDetail(userId, tableGroupId);
	}
	
	// -------------------- joinTableGroup --------------------
	
	@Test
	void joinTableGroup_whenCalled_shouldDelegateToServiceWithCurrentUserId() throws Exception {
		// given
		UUID tableGroupId = UUID.randomUUID();
		
		doNothing().when(tableGroupService).joinTableGroup(userId, tableGroupId, null);
		
		// when & then
		mockMvc.perform(
				       post(EndPoints.TableGroup.BASE + EndPoints.TableGroup.JOIN, tableGroupId)
						       .principal(principal())
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.message").value("Masaya katilim istegi gonderildi"));
		
		verify(tableGroupService).joinTableGroup(userId, tableGroupId, null);
	}
	
	// -------------------- approveJoinRequest --------------------
	
	@Test
	void approveJoinRequest_whenOwnerCalls_shouldDelegateToService() throws Exception {
		// given
		UUID tableGroupId = UUID.randomUUID();
		UUID participantId = UUID.randomUUID();
		
		doNothing().when(tableGroupService)
		           .approveJoinRequest(userId, tableGroupId, participantId);
		
		// when & then
		mockMvc.perform(
				       post(EndPoints.TableGroup.BASE + EndPoints.TableGroup.APPROVE, tableGroupId, participantId)
						       .principal(principal())
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.message").value("Katilim istegi onaylandi"));
		
		verify(tableGroupService).approveJoinRequest(userId, tableGroupId, participantId);
	}
}
