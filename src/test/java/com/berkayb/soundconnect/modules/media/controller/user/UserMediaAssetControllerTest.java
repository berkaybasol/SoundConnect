// src/test/java/com/berkayb/soundconnect/modules/media/controller/user/UserMediaAssetControllerTest.java
package com.berkayb.soundconnect.modules.media.controller.user;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.modules.media.dto.request.CompleteUploadRequestDto;
import com.berkayb.soundconnect.modules.media.dto.request.UploadInitRequestDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.transcode.TranscodePublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Media.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("web")
class UserMediaAssetControllerTest {
	
	@Autowired MockMvc mockMvc;
	@Autowired MediaAssetRepository mediaRepo;
	
	// Dış bağımlılıklar – context'i sakinleştirmek için mock
	@MockitoBean StorageClient storageClient;
	@MockitoBean TranscodePublisher transcodePublisher;
	@MockitoBean RabbitTemplate rabbitTemplate;
	
	private final ObjectMapper om = new ObjectMapper();
	
	UUID ownerId;
	UUID actingUserId;
	
	@BeforeEach
	void setup() {
		mediaRepo.deleteAll();
		ownerId = UUID.randomUUID();
		actingUserId = UUID.randomUUID();
	}
	
	@Test
	void initUpload_ok_createsDraftAndReturnsPresigned() throws Exception {
		// Storage davranışları
		when(storageClient.createPresignedPutUrl(any(), eq("image/png")))
				.thenReturn("https://upload.url/presigned");
		when(storageClient.publicUrl(any()))
				.thenAnswer(inv -> "https://cdn.test/" + inv.getArgument(0, String.class));
		
		UploadInitRequestDto dto = UploadInitRequestDto.builder()
		                                               .ownerType(MediaOwnerType.USER)
		                                               .ownerId(ownerId)
		                                               .kind(MediaKind.IMAGE)
		                                               .visibility(MediaVisibility.PUBLIC)
		                                               .mimeType("image/png")
		                                               .sizeBytes(12345L)
		                                               .originalFileName("cover.PNG")
		                                               .build();
		
		mockMvc.perform(post(USER_BASE + INIT_UPLOAD)
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(om.writeValueAsString(dto)))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.assetId").isNotEmpty())
		       .andExpect(jsonPath("$.data.uploadUrl").value("https://upload.url/presigned"));
		
		// Repo'da draft oluşturulmuş olmalı
		assertThat(mediaRepo.count()).isEqualTo(1);
		MediaAsset draft = mediaRepo.findAll().get(0);
		assertThat(draft.getStatus()).isEqualTo(MediaStatus.UPLOADING);
		assertThat(draft.getOwnerType()).isEqualTo(MediaOwnerType.USER);
		assertThat(draft.getOwnerId()).isEqualTo(ownerId);
		assertThat(draft.getSourceUrl()).startsWith("https://cdn.test/");
		assertThat(draft.getStorageKey()).isNotBlank();
		assertThat(draft.getMimeType()).isEqualTo("image/png");
		assertThat(draft.getSize()).isEqualTo(12345L);
	}
	
	@Test
	void completeUpload_image_becomesReady_andReturnsDto() throws Exception {
		// Non-video -> READY ve playbackUrl = sourceUrl
		MediaAsset asset = mediaRepo.save(MediaAsset.builder()
		                                            .kind(MediaKind.IMAGE)
		                                            .status(MediaStatus.UPLOADING)
		                                            .visibility(MediaVisibility.PUBLIC)
		                                            .ownerType(MediaOwnerType.USER)
		                                            .ownerId(ownerId)
		                                            .mimeType("image/png")
		                                            .size(100L)
		                                            .storageKey("media/" + UUID.randomUUID() + "/source.png")
		                                            .sourceUrl("https://cdn.test/source.png")
		                                            .build());
		
		CompleteUploadRequestDto body = CompleteUploadRequestDto.builder()
		                                                        .assetId(asset.getId())
		                                                        .build();
		
		mockMvc.perform(post(USER_BASE + COMPLETE_UPLOAD)
				                .contentType(MediaType.APPLICATION_JSON)
				                .content(om.writeValueAsString(body)))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.uuid").value(asset.getId().toString()))
		       .andExpect(jsonPath("$.data.status").value("READY"))
		       .andExpect(jsonPath("$.data.playbackUrl").value("https://cdn.test/source.png"));
		
		MediaAsset refreshed = mediaRepo.findById(asset.getId()).orElseThrow();
		assertThat(refreshed.getStatus()).isEqualTo(MediaStatus.READY);
		assertThat(refreshed.getPlaybackUrl()).isEqualTo(refreshed.getSourceUrl());
	}
	
	@Test
	void listByOwner_ok_returnsAllStatusesForOwner() throws Exception {
		// ownerId için 3 farklı durumda kayıt
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.IMAGE)
		                         .status(MediaStatus.READY)
		                         .visibility(MediaVisibility.PRIVATE)
		                         .ownerType(MediaOwnerType.USER)
		                         .ownerId(ownerId)
		                         .mimeType("image/png")
		                         .size(1L)
		                         .storageKey("a")
		                         .sourceUrl("u")
		                         .build());
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.VIDEO)
		                         .status(MediaStatus.PROCESSING)
		                         .visibility(MediaVisibility.PUBLIC)
		                         .ownerType(MediaOwnerType.USER)
		                         .ownerId(ownerId)
		                         .mimeType("video/mp4")
		                         .size(2L)
		                         .storageKey("b")
		                         .sourceUrl("u2")
		                         .build());
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.AUDIO)
		                         .status(MediaStatus.UPLOADING)
		                         .visibility(MediaVisibility.UNLISTED)
		                         .ownerType(MediaOwnerType.USER)
		                         .ownerId(ownerId)
		                         .mimeType("audio/mpeg")
		                         .size(3L)
		                         .storageKey("c")
		                         .sourceUrl("u3")
		                         .build());
		
		// başka owner
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.IMAGE)
		                         .status(MediaStatus.READY)
		                         .visibility(MediaVisibility.PUBLIC)
		                         .ownerType(MediaOwnerType.USER)
		                         .ownerId(UUID.randomUUID())
		                         .mimeType("image/png")
		                         .size(1L)
		                         .storageKey("x")
		                         .sourceUrl("ux")
		                         .build());
		
		mockMvc.perform(get(USER_BASE + LIST_BY_OWNER, MediaOwnerType.USER, ownerId)
				                .param("page","0").param("size","10"))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.content", hasSize(3)));
	}
	
	@Test
	void listByOwnerAndKind_ok_filtersByKind() throws Exception {
		// ownerId için IMAGE + VIDEO
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.IMAGE)
		                         .status(MediaStatus.READY)
		                         .visibility(MediaVisibility.PUBLIC)
		                         .ownerType(MediaOwnerType.USER)
		                         .ownerId(ownerId)
		                         .mimeType("image/png")
		                         .size(1L)
		                         .storageKey("a")
		                         .sourceUrl("u")
		                         .build());
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.VIDEO)
		                         .status(MediaStatus.READY)
		                         .visibility(MediaVisibility.PUBLIC)
		                         .ownerType(MediaOwnerType.USER)
		                         .ownerId(ownerId)
		                         .mimeType("video/mp4")
		                         .size(1L)
		                         .storageKey("b")
		                         .sourceUrl("v")
		                         .build());
		
		mockMvc.perform(get(USER_BASE + LIST_BY_OWNER_AND_KIND, MediaOwnerType.USER, ownerId, MediaKind.IMAGE)
				                .param("page","0").param("size","10"))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.content", hasSize(1)))
		       .andExpect(jsonPath("$.data.content[0].kind").value("IMAGE"));
	}
	
	@Test
	void delete_ok_ownerMatches_deletesFromDb_andStorageCalled() throws Exception {
		MediaAsset asset = mediaRepo.save(MediaAsset.builder()
		                                            .kind(MediaKind.VIDEO)
		                                            .status(MediaStatus.READY)
		                                            .visibility(MediaVisibility.PUBLIC)
		                                            .ownerType(MediaOwnerType.USER)
		                                            .ownerId(ownerId)
		                                            .mimeType("video/mp4")
		                                            .size(42L)
		                                            .storageKey("media/" + UUID.randomUUID() + "/source.mp4")
		                                            .sourceUrl("https://cdn.test/source.mp4")
		                                            .build());
		
		mockMvc.perform(delete(USER_BASE + DELETE, asset.getId())
				                .param("actingUserId", actingUserId.toString())
				                .param("actingAsType", MediaOwnerType.USER.name())
				                .param("actingAsId", ownerId.toString()))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true));
		
		assertThat(mediaRepo.findById(asset.getId())).isEmpty();
	}
}