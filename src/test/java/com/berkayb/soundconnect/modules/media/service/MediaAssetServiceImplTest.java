// src/test/java/com/berkayb/soundconnect/modules/media/service/MediaAssetServiceImplTest.java
package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.modules.media.dto.response.UploadInitResultResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.transcode.TranscodePublisher;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = SoundConnectApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("service")
class MediaAssetServiceImplTest {
	
	@Autowired MediaAssetService mediaService;
	@Autowired MediaAssetRepository mediaRepo;
	
	// dış bağımlılıklar
	@MockitoBean StorageClient storageClient;
	@MockitoBean MediaPolicy mediaPolicy;
	@MockitoBean TranscodePublisher transcodePublisher;
	@MockitoBean RabbitTemplate rabbitTemplate; // context’te başka yerler isteyebilir
	
	UUID ownerId;
	
	@BeforeEach
	void setup() {
		mediaRepo.deleteAll();
		ownerId = UUID.randomUUID();
	}
	
	// --------------------- initUpload ---------------------
	
	@Test
	void initUpload_image_ok_persistsDraft_andReturnsPresignedUrl_andSourceUrl() {
		// arrange policy + storage
		doNothing().when(mediaPolicy).validate(eq(MediaKind.IMAGE), eq("image/png"), eq(12345L));
		
		when(mediaPolicy.buildSourceKey(any(), eq("cover.png")))
				.thenAnswer(inv -> "media/" + inv.getArgument(0) + "/source.png");
		when(storageClient.createPresignedPutUrl(startsWith("media/"), eq("image/png")))
				.thenReturn("https://upload.presigned/url");
		when(storageClient.publicUrl(startsWith("media/")))
				.thenAnswer(inv -> "https://cdn.test/" + inv.getArgument(0, String.class));
		
		// act
		UploadInitResultResponseDto res = mediaService.initUpload(
				MediaOwnerType.USER, ownerId, MediaKind.IMAGE, MediaVisibility.PUBLIC,
				"image/png", 12345L, "cover.png"
		);
		
		// assert response
		assertThat(res.assetId()).isNotNull();
		assertThat(res.uploadUrl()).isEqualTo("https://upload.presigned/url");
		
		// assert DB draft
		MediaAsset draft = mediaRepo.findById(res.assetId()).orElseThrow();
		assertThat(draft.getStatus()).isEqualTo(MediaStatus.UPLOADING);
		assertThat(draft.getOwnerType()).isEqualTo(MediaOwnerType.USER);
		assertThat(draft.getOwnerId()).isEqualTo(ownerId);
		assertThat(draft.getKind()).isEqualTo(MediaKind.IMAGE);
		assertThat(draft.getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
		assertThat(draft.getSourceUrl()).startsWith("https://cdn.test/media/");
		assertThat(draft.getStorageKey()).startsWith("media/");
		assertThat(draft.getStreamingProtocol()).isEqualTo(MediaStreamingProtocol.PROGRESSIVE);
	}
	
	@Test
	void initUpload_video_ok_setsStreamingProtocolHls_andQueuesNothingYet() {
		doNothing().when(mediaPolicy).validate(eq(MediaKind.VIDEO), eq("video/mp4"), eq(100L));
		when(mediaPolicy.buildSourceKey(any(), any()))
				.thenAnswer(inv -> "media/" + inv.getArgument(0) + "/source.mp4");
		when(storageClient.createPresignedPutUrl(startsWith("media/"), eq("video/mp4")))
				.thenReturn("https://upload.presigned/url");
		when(storageClient.publicUrl(startsWith("media/")))
				.thenAnswer(inv -> "https://cdn.test/" + inv.getArgument(0, String.class));
		
		UploadInitResultResponseDto res = mediaService.initUpload(
				MediaOwnerType.USER, ownerId, MediaKind.VIDEO, MediaVisibility.UNLISTED,
				"video/mp4", 100L, "clip.mp4"
		);
		
		MediaAsset draft = mediaRepo.findById(res.assetId()).orElseThrow();
		assertThat(draft.getStreamingProtocol()).isEqualTo(MediaStreamingProtocol.HLS);
		// henüz transcode kuyruğa atılmaz; completeUpload’ta atılıyor
		verifyNoInteractions(transcodePublisher);
	}
	
	// --------------------- completeUpload ---------------------
	
	@Test
	void completeUpload_nonVideo_makesReady_andPlaybackEqualsSource() {
		MediaAsset image = mediaRepo.save(MediaAsset.builder()
		                                            .kind(MediaKind.IMAGE)
		                                            .status(MediaStatus.UPLOADING)
		                                            .visibility(MediaVisibility.PUBLIC)
		                                            .ownerType(MediaOwnerType.USER)
		                                            .ownerId(ownerId)
		                                            .mimeType("image/png")
		                                            .size(10L)
		                                            .storageKey("media/" + UUID.randomUUID() + "/source.png")
		                                            .sourceUrl("https://cdn.test/source.png")
		                                            .build());
		
		MediaAsset done = mediaService.completeUpload(image.getId());
		
		assertThat(done.getStatus()).isEqualTo(MediaStatus.READY);
		assertThat(done.getPlaybackUrl()).isEqualTo(done.getSourceUrl());
		verifyNoInteractions(transcodePublisher);
	}
	
	@Test
	void completeUpload_video_setsProcessing_andPublishesTranscodeJob() {
		MediaAsset video = mediaRepo.save(MediaAsset.builder()
		                                            .kind(MediaKind.VIDEO)
		                                            .status(MediaStatus.UPLOADING)
		                                            .visibility(MediaVisibility.PUBLIC)
		                                            .ownerType(MediaOwnerType.USER)
		                                            .ownerId(ownerId)
		                                            .mimeType("video/mp4")
		                                            .size(100L)
		                                            .storageKey("media/" + UUID.randomUUID() + "/source.mp4")
		                                            .sourceUrl("https://cdn.test/source.mp4")
		                                            .build());
		
		when(mediaPolicy.buildHlsPrefix(eq(video.getId())))
				.thenReturn("media/" + video.getId() + "/hls");
		
		MediaAsset after = mediaService.completeUpload(video.getId());
		
		assertThat(after.getStatus()).isEqualTo(MediaStatus.PROCESSING);
		
		// transcode publish edildi mi?
		ArgumentCaptor<UUID> idCap = ArgumentCaptor.forClass(UUID.class);
		ArgumentCaptor<String> keyCap = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> hlsCap = ArgumentCaptor.forClass(String.class);
		verify(transcodePublisher, times(1))
				.publishVideoHls(idCap.capture(), keyCap.capture(), hlsCap.capture());
		
		assertThat(idCap.getValue()).isEqualTo(video.getId());
		assertThat(keyCap.getValue()).isEqualTo(video.getStorageKey());
		assertThat(hlsCap.getValue()).isEqualTo("media/" + video.getId() + "/hls");
	}
	
	// --------------------- list* ---------------------
	
	@Test
	void list_methods_delegateToRepo_andReturnPages() {
		// owner’a ait 2 kayıt
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.IMAGE).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
		                         .ownerType(MediaOwnerType.USER).ownerId(ownerId)
		                         .mimeType("image/png").size(1L).storageKey("k1").sourceUrl("u1").build());
		mediaRepo.save(MediaAsset.builder()
		                         .kind(MediaKind.VIDEO).status(MediaStatus.PROCESSING).visibility(MediaVisibility.PRIVATE)
		                         .ownerType(MediaOwnerType.USER).ownerId(ownerId)
		                         .mimeType("video/mp4").size(2L).storageKey("k2").sourceUrl("u2").build());
		
		Page<MediaAsset> all = mediaService.listByOwner(MediaOwnerType.USER, ownerId, PageRequest.of(0, 10));
		assertThat(all.getTotalElements()).isEqualTo(2);
		
		Page<MediaAsset> imgs = mediaService.listByOwnerAndKind(
				MediaOwnerType.USER, ownerId, MediaKind.IMAGE, PageRequest.of(0, 10));
		assertThat(imgs.getTotalElements()).isEqualTo(1);
		
		// public+ready filtreleri service içinde repo sorgularıyla
		Page<MediaAsset> publicReady = mediaService.listPublicByOwner(
				MediaOwnerType.USER, ownerId, PageRequest.of(0, 10));
		assertThat(publicReady.getTotalElements()).isEqualTo(1);
		assertThat(publicReady.getContent().get(0).getStatus()).isEqualTo(MediaStatus.READY);
		assertThat(publicReady.getContent().get(0).getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
	}
	
	// --------------------- delete ---------------------
	
	@Test
	void delete_ownerMismatch_throwsForbidden() {
		MediaAsset asset = mediaRepo.save(MediaAsset.builder()
		                                            .kind(MediaKind.IMAGE)
		                                            .status(MediaStatus.READY)
		                                            .visibility(MediaVisibility.PUBLIC)
		                                            .ownerType(MediaOwnerType.USER)
		                                            .ownerId(ownerId)
		                                            .mimeType("image/png")
		                                            .size(1L)
		                                            .storageKey("media/" + UUID.randomUUID() + "/x.png")
		                                            .sourceUrl("u")
		                                            .build());
		
		UUID actingUserId = UUID.randomUUID();
		UUID wrongOwnerId = UUID.randomUUID();
		
		assertThatThrownBy(() -> mediaService.delete(
				asset.getId(), actingUserId, MediaOwnerType.USER, wrongOwnerId
		)).isInstanceOf(SoundConnectException.class);
		
		// silinmemiş olmalı
		assertThat(mediaRepo.findById(asset.getId())).isPresent();
		verifyNoInteractions(storageClient);
	}
	
	@Test
	void delete_ownerMatch_deletesDb_andStorageObject_forImage() {
		MediaAsset asset = mediaRepo.save(MediaAsset.builder()
		                                            .kind(MediaKind.IMAGE)
		                                            .status(MediaStatus.READY)
		                                            .visibility(MediaVisibility.PUBLIC)
		                                            .ownerType(MediaOwnerType.USER)
		                                            .ownerId(ownerId)
		                                            .mimeType("image/png")
		                                            .size(1L)
		                                            .storageKey("media/" + UUID.randomUUID() + "/img.png")
		                                            .sourceUrl("u")
		                                            .build());
		
		mediaService.delete(asset.getId(), UUID.randomUUID(), MediaOwnerType.USER, ownerId);
		
		assertThat(mediaRepo.findById(asset.getId())).isEmpty();
		verify(storageClient, times(1)).deleteObject(eq(asset.getStorageKey()));
		// image için folder silinmez
		verify(storageClient, never()).deleteFolder(anyString());
	}
	
	@Test
	void delete_ownerMatch_video_deletesDb_object_andHlsFolder() {
		// 1) Önce ID’yi JPA üretsin diye id vermeden kaydediyoruz (storageKey şimdilik geçici)
		MediaAsset asset = mediaRepo.save(MediaAsset.builder()
		                                            .kind(MediaKind.VIDEO)
		                                            .status(MediaStatus.READY)
		                                            .visibility(MediaVisibility.PUBLIC)
		                                            .ownerType(MediaOwnerType.USER)
		                                            .ownerId(ownerId)
		                                            .mimeType("video/mp4")
		                                            .size(1L)
		                                            .storageKey("media/tmp/source.mp4")     // geçici bir şey; NOT NULL zorunluluğu için
		                                            .sourceUrl("u")
		                                            .build());
		
		// 2) Artık ID var; istersek storageKey’i ID’li hale getirip tekrar save edebiliriz (opsiyonel)
		String realKey = "media/" + asset.getId() + "/source.mp4";
		asset.setStorageKey(realKey);
		asset = mediaRepo.save(asset);
		
		// HLS prefix’ini service tarafının kullanacağı şekilde stub’la
		when(mediaPolicy.buildHlsPrefix(eq(asset.getId())))
				.thenReturn("media/" + asset.getId() + "/hls");
		
		// 3) Sil
		mediaService.delete(asset.getId(), UUID.randomUUID(), MediaOwnerType.USER, ownerId);
		
		// 4) DB ve storage doğrulamaları
		assertThat(mediaRepo.findById(asset.getId())).isEmpty();
		verify(storageClient, times(1)).deleteObject(eq(realKey));
		
		// deleteFolder çağrısında doğru prefix’i kullandığını esnek kontrol et
		ArgumentCaptor<String> prefixCap = ArgumentCaptor.forClass(String.class);
		verify(storageClient, times(1)).deleteFolder(prefixCap.capture());
		
		String usedPrefix = prefixCap.getValue();
		assertThat(usedPrefix).contains(asset.getId().toString());
		assertThat(usedPrefix).startsWith("media/" + asset.getId() + "/");
	}
}