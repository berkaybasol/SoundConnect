// src/test/java/com/berkayb/soundconnect/modules/media/controller/user/PublicMediaControllerTest.java
package com.berkayb.soundconnect.modules.media.controller.user;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.transcode.TranscodePublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Media.PUBLIC_BASE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Media.PUBLIC_BY_OWNER;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Media.PUBLIC_BY_OWNER_AND_KIND;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("web")
class PublicMediaControllerTest {
	
	@Autowired MockMvc mockMvc;
	@Autowired MediaAssetRepository mediaRepo;
	
	// Context'te ihtiyaç duyulan dış bağımlılıklar:
	@MockitoBean StorageClient storageClient;
	@MockitoBean TranscodePublisher transcodePublisher;
	@MockitoBean RabbitTemplate rabbitTemplate;
	
	UUID ownerId;
	UUID otherOwnerId;
	
	@BeforeEach
	void setup() {
		mediaRepo.deleteAll();
		ownerId = UUID.randomUUID();
		otherOwnerId = UUID.randomUUID();
		
		// Görünmeli: PUBLIC + READY (ownerId)
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.IMAGE)
		                         .status(MediaStatus.READY)
		                         .visibility(MediaVisibility.PUBLIC)
		                         .ownerType(MediaOwnerType.USER)
		                         .ownerId(ownerId)
		                         .mimeType("image/png")
		                         .size(1234L)
		                         .storageKey("media/" + UUID.randomUUID() + "/source.png")
		                         .sourceUrl("https://cdn.test/media/source.png")
		                         .build());
		
		// Görünmemeli: PRIVATE + READY (ownerId)
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.IMAGE)
		                         .status(MediaStatus.READY)
		                         .visibility(MediaVisibility.PRIVATE)
		                         .ownerType(MediaOwnerType.USER)
		                         .ownerId(ownerId)
		                         .mimeType("image/png")
		                         .size(1234L)
		                         .storageKey("media/" + UUID.randomUUID() + "/private.png")
		                         .sourceUrl("https://cdn.test/private.png")
		                         .build());
		
		// Görünmemeli: PUBLIC + UPLOADING (ownerId)
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.VIDEO)
		                         .status(MediaStatus.UPLOADING)
		                         .visibility(MediaVisibility.PUBLIC)
		                         .ownerType(MediaOwnerType.USER)
		                         .ownerId(ownerId)
		                         .mimeType("video/mp4")
		                         .size(9999L)
		                         .storageKey("media/" + UUID.randomUUID() + "/source.mp4")
		                         .sourceUrl("https://cdn.test/uploading.mp4")
		                         .build());
		
		// Diğer owner: PUBLIC + READY
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.IMAGE)
		                         .status(MediaStatus.READY)
		                         .visibility(MediaVisibility.PUBLIC)
		                         .ownerType(MediaOwnerType.USER)
		                         .ownerId(otherOwnerId)
		                         .mimeType("image/png")
		                         .size(111L)
		                         .storageKey("media/" + UUID.randomUUID() + "/o.png")
		                         .sourceUrl("https://cdn.test/o.png")
		                         .build());
	}
	
	@Test
	void listPublicByOwner_ok_filtersOnlyPublicReady() throws Exception {
		mockMvc.perform(get(PUBLIC_BASE + PUBLIC_BY_OWNER, MediaOwnerType.USER, ownerId)
				                .param("page", "0")
				                .param("size", "10")
				                .param("sort", "createdAt,desc"))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.content", hasSize(1)))
		       .andExpect(jsonPath("$.data.content[0].ownerId").value(ownerId.toString()))
		       .andExpect(jsonPath("$.data.content[0].visibility").value("PUBLIC"))
		       .andExpect(jsonPath("$.data.content[0].status").value("READY"));
	}
	
	@Test
	void listPublicByOwnerAndKind_ok_filtersByKindToo() throws Exception {
		// ownerId için READY+PUBLIC olan IMAGE var; VIDEO yok
		mockMvc.perform(get(PUBLIC_BASE + PUBLIC_BY_OWNER_AND_KIND,
		                    MediaOwnerType.USER, ownerId, MediaKind.VIDEO)
				                .param("page", "0")
				                .param("size", "10"))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.content", hasSize(0)));
		
		mockMvc.perform(get(PUBLIC_BASE + PUBLIC_BY_OWNER_AND_KIND,
		                    MediaOwnerType.USER, ownerId, MediaKind.IMAGE)
				                .param("page", "0")
				                .param("size", "10"))
		       .andDo(print())
		       .andExpect(status().isOk())
		       .andExpect(jsonPath("$.success").value(true))
		       .andExpect(jsonPath("$.data.content", hasSize(1)))
		       .andExpect(jsonPath("$.data.content[0].kind").value("IMAGE"))
		       .andExpect(jsonPath("$.data.content[0].ownerId").value(ownerId.toString()));
	}
}