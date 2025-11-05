package com.berkayb.soundconnect.modules.tablegroup.controller;

import com.berkayb.soundconnect.modules.tablegroup.dto.request.TableGroupCreateRequestDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupParticipantDto;
import com.berkayb.soundconnect.modules.tablegroup.dto.response.TableGroupResponseDto;
import com.berkayb.soundconnect.modules.tablegroup.enums.ParticipantStatus;
import com.berkayb.soundconnect.modules.tablegroup.enums.TableGroupStatus;
import com.berkayb.soundconnect.modules.tablegroup.service.TableGroupService;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
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
	
	@Mock
	private UserRepository userRepository;
	
	@InjectMocks
	private TableGroupController controller;
	
	private MockMvc mockMvc;
	private ObjectMapper objectMapper;
	
	private UUID userId;
	private String username;
	
	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
		                         .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
		                         .build();
		
		objectMapper = new ObjectMapper();
		objectMapper.registerModule(new JavaTimeModule());
		objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		
		userId = UUID.randomUUID();
		username = "testuser";
		
		User user = User.builder()
		                .id(userId)
		                .username(username)
		                .build();
		
		// Bazı testlerde principal kullanılmıyor, o yüzden lenient
		lenient().when(userRepository.findByUsername(username))
		         .thenReturn(Optional.of(user));
	}
	
	private Principal principal() {
		return () -> username;
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
						LocalDateTime.now(),
						ParticipantStatus.ACCEPTED
				)
		);
		
		return new TableGroupResponseDto(
				tableGroupId,
				userId,          // ownerId
				null,            // venueId
				"My Venue",
				3,
				genders,
				20,
				30,
				LocalDateTime.now().plusHours(2),
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
	void createTableGroup_whenValid_shouldReturn201AndBaseResponseWithDto() throws Exception {
		// given
		UUID cityId = UUID.randomUUID();
		TableGroupCreateRequestDto requestDto = new TableGroupCreateRequestDto(
				null,
				"My Venue",
				3,
				List.of("MALE", "FEMALE", "OTHER"),
				20,
				30,
				LocalDateTime.now().plusHours(2),
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
		       .andExpect(jsonPath("$.data.ownerId").value(userId.toString()));
		
		verify(tableGroupService).createTableGroup(eq(userId), any(TableGroupCreateRequestDto.class));
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
		
		when(tableGroupService.listActiveTableGroups(eq(cityId), isNull(), isNull(), any(Pageable.class)))
				.thenReturn(page);
		
		Pageable pageable = Pageable.unpaged();
		
		// when: MockMvc ile degil, controller metodunu direkt cagiriyoruz
		ResponseEntity<BaseResponse<Page<TableGroupResponseDto>>> response =
				controller.listActiveTableGroups(cityId, null, null, pageable);
		
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
		
		verify(tableGroupService).listActiveTableGroups(eq(cityId), isNull(), isNull(), any(Pageable.class));
	}
	
	
	// -------------------- getTableGroupDetail --------------------
	
	@Test
	void getTableGroupDetail_whenExists_shouldReturnDtoInBaseResponse() throws Exception {
		// given
		UUID tableGroupId = UUID.randomUUID();
		TableGroupResponseDto dto = sampleResponseDto(tableGroupId);
		
		when(tableGroupService.getTableGroupDetail(tableGroupId))
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
		
		verify(tableGroupService).getTableGroupDetail(tableGroupId);
	}
	
	// -------------------- joinTableGroup --------------------
	
	@Test
	void joinTableGroup_whenCalled_shouldDelegateToServiceWithCurrentUserId() throws Exception {
		// given
		UUID tableGroupId = UUID.randomUUID();
		
		doNothing().when(tableGroupService).joinTableGroup(userId, tableGroupId);
		
		// when & then
		mockMvc.perform(
				       post(EndPoints.TableGroup.BASE + EndPoints.TableGroup.JOIN, tableGroupId)
						       .principal(principal())
		       )
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.code").value(200))
		       .andExpect(jsonPath("$.message").value("Masaya katilim istegi gonderildi"));
		
		verify(tableGroupService).joinTableGroup(userId, tableGroupId);
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