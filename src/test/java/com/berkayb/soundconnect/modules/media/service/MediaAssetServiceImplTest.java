// src/test/java/com/berkayb/soundconnect/modules/media/service/MediaAssetServiceImplTest.java
package com.berkayb.soundconnect.modules.media.service;

import com.berkayb.soundconnect.SoundConnectApplication;
import com.berkayb.soundconnect.auth.otp.service.OtpService;
import com.berkayb.soundconnect.modules.comment.entity.Comment;
import com.berkayb.soundconnect.modules.comment.repository.CommentRepository;
import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.like.entity.Like;
import com.berkayb.soundconnect.modules.like.repository.LikeRepository;
import com.berkayb.soundconnect.modules.media.dto.response.UploadInitResultResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import com.berkayb.soundconnect.modules.media.image.ImageThumbnailService;
import com.berkayb.soundconnect.modules.media.image.ImageVariantJobDispatcher;
import com.berkayb.soundconnect.modules.media.deletion.MediaDeletionDispatcher;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.media.storage.MediaPolicy;
import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import com.berkayb.soundconnect.modules.media.storage.StorageObjectMetadata;
import com.berkayb.soundconnect.modules.media.transcode.TranscodePublisher;
import com.berkayb.soundconnect.modules.profile.shared.media.entity.ProfileMedia;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.profile.shared.media.repository.ProfileMediaRepository;
import com.berkayb.soundconnect.modules.user.entity.User;
import com.berkayb.soundconnect.modules.user.enums.UserStatus;
import com.berkayb.soundconnect.modules.user.repository.UserRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.mail.adapter.MailSenderClient;
import com.berkayb.soundconnect.shared.mail.helper.MailJobHelper;
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
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.io.ByteArrayInputStream;

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
	@Autowired ProfileMediaRepository profileMediaRepository;
	@Autowired LikeRepository likeRepository;
	@Autowired CommentRepository commentRepository;
	@Autowired UserRepository userRepository;
	@Autowired PlatformTransactionManager transactionManager;
	
	// dış bağımlılıklar
	@MockitoBean StorageClient storageClient;
	@MockitoBean MediaPolicy mediaPolicy;
	@MockitoBean ImageThumbnailService imageThumbnailService;
	@MockitoBean ImageVariantJobDispatcher imageVariantJobDispatcher;
	@MockitoBean MediaDeletionDispatcher mediaDeletionDispatcher;
	@MockitoBean TranscodePublisher transcodePublisher;
	// MailProducerImpl yüzünden gerekecek
	@MockitoBean RabbitTemplate rabbitTemplate;
	@MockitoBean
	RedisConnectionFactory redisConnectionFactory;
	@MockitoBean
	RedisTemplate<String, String> redisTemplate;
	@MockitoBean
	OtpService otpService;
	
	@MockitoBean
	StringRedisTemplate stringRedisTemplate;
	
	@MockitoBean
	MailJobHelper mailJobHelper;
	
	@MockitoBean
	MailSenderClient mailSenderClient;
	
	@MockitoBean
	org.springframework.amqp.support.converter.Jackson2JsonMessageConverter jackson2JsonMessageConverter;
	
	// Bazı config'ler ConnectionFactory isterse güvence:
	@MockitoBean(name = "rabbitConnectionFactory")
	org.springframework.amqp.rabbit.connection.CachingConnectionFactory rabbitConnectionFactory;
	
	
	// İstersen tüketiciyi de körle (gerekmeden geçmesi lazım ama garanti):
	@MockitoBean
	com.berkayb.soundconnect.shared.mail.consumer.DlqMailJobConsumer dlqMailJobConsumer;
	
	UUID ownerId;
	
	@BeforeEach
	void setup() {
		commentRepository.deleteAll();
		likeRepository.deleteAll();
		profileMediaRepository.deleteAll();
		mediaRepo.deleteAll();
		ownerId = UUID.randomUUID();
	}
	
	// --------------------- initUpload ---------------------
	
	@Test
	void initUpload_image_ok_persistsQuarantinedDraft_withoutPublicUrl() {
		// arrange policy + storage
		doNothing().when(mediaPolicy).validate(eq(MediaKind.IMAGE), eq("image/png"), eq(12345L));
		
		when(mediaPolicy.buildSourceKey(any(), eq("image/png")))
				.thenAnswer(inv -> "media/" + inv.getArgument(0) + "/source.png");
		when(storageClient.createPresignedPutUrl(startsWith("quarantine/media/"), eq("image/png"), eq(12345L)))
				.thenReturn("https://upload.presigned/url");
		
		// act
		UploadInitResultResponseDto res = mediaService.initUpload(
				ownerId, MediaOwnerType.USER, ownerId, MediaKind.IMAGE, MediaVisibility.PUBLIC,
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
		assertThat(draft.getSourceUrl()).isNull();
		assertThat(draft.getStorageKey()).startsWith("quarantine/media/");
		assertThat(draft.getStreamingProtocol()).isEqualTo(MediaStreamingProtocol.PROGRESSIVE);
		verify(storageClient, never()).publicUrl(anyString());
	}
	
	@Test
	void initUpload_video_ok_setsStreamingProtocolHls_andQueuesNothingYet() {
		doNothing().when(mediaPolicy).validate(eq(MediaKind.VIDEO), eq("video/mp4"), eq(100L));
		when(mediaPolicy.buildSourceKey(any(), any()))
				.thenAnswer(inv -> "media/" + inv.getArgument(0) + "/source.mp4");
		when(storageClient.createPresignedPutUrl(startsWith("quarantine/media/"), eq("video/mp4"), eq(100L)))
				.thenReturn("https://upload.presigned/url");
		
		UploadInitResultResponseDto res = mediaService.initUpload(
				ownerId, MediaOwnerType.USER, ownerId, MediaKind.VIDEO, MediaVisibility.PUBLIC,
				"video/mp4", 100L, "clip.mp4"
		);
		
		MediaAsset draft = mediaRepo.findById(res.assetId()).orElseThrow();
		assertThat(draft.getStreamingProtocol()).isEqualTo(MediaStreamingProtocol.HLS);
		assertThat(draft.getStorageKey()).startsWith("quarantine/media/");
		assertThat(draft.getSourceUrl()).isNull();
		verify(storageClient, never()).publicUrl(anyString());
		// henüz transcode kuyruğa atılmaz; completeUpload’ta atılıyor
		verifyNoInteractions(transcodePublisher);
	}
	
	// --------------------- completeUpload ---------------------
	
	@Test
	void completeUpload_image_keepsReadySourceFallback_whenVariantWorkerIsDisabled() throws Exception {
		MediaAsset image = mediaRepo.save(MediaAsset.builder()
		                                            .kind(MediaKind.IMAGE)
		                                            .status(MediaStatus.UPLOADING)
		                                            .visibility(MediaVisibility.PUBLIC)
		                                            .ownerType(MediaOwnerType.USER)
		                                            .ownerId(ownerId)
		                                            .mimeType("image/png")
		                                            .size(10L)
		                                            .storageKey("quarantine/media/" + UUID.randomUUID() + "/source.png")
		                                            .build());
		
		String mutableKey = image.getStorageKey();
		when(storageClient.getObjectMetadata(mutableKey))
				.thenReturn(Optional.of(new StorageObjectMetadata(10L, "image/png", "etag")));
		when(storageClient.getObjectMetadata(argThat(key -> key != null
				&& key.startsWith("verified/") && key.contains("/attempts/"))))
				.thenReturn(Optional.of(new StorageObjectMetadata(10L, "image/png", "immutable-etag")));
		when(storageClient.getObjectStream(argThat(key -> key != null
				&& key.startsWith("verified/") && key.contains("/attempts/")))).thenReturn(
				new ByteArrayInputStream(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A})
		);
		when(storageClient.publicUrl(argThat(key -> key != null
				&& !key.startsWith("verified/") && key.contains("/attempts/"))))
				.thenAnswer(invocation -> "https://cdn.test/" + invocation.getArgument(0));
		MediaAsset done = mediaService.completeUpload(ownerId, image.getId());
		
		assertThat(done.getStatus()).isEqualTo(MediaStatus.READY);
		assertThat(done.getPlaybackUrl()).isEqualTo(done.getSourceUrl());
		assertThat(done.getStorageKey()).startsWith("media/").contains("/attempts/");
		assertThat(done.getThumbnailUrl()).isNull();
		assertThat(done.getWidth()).isNull();
		assertThat(done.getHeight()).isNull();
		String immutableKey = "verified/" + done.getStorageKey();
		verify(storageClient).copyUploadToImmutable(mutableKey, immutableKey, "etag");
		verify(storageClient).promoteVerifiedObject(
				immutableKey, done.getStorageKey(), "image/png",
				"public, max-age=300, s-maxage=3600, stale-while-revalidate=60",
				"immutable-etag");
		// Native image work is intentionally disabled in the test profile. The
		// READY source remains authoritative and scheduled backfill can retry later.
		verifyNoInteractions(imageVariantJobDispatcher);
		verifyNoInteractions(transcodePublisher);
	}
	
	@Test
	void completeUpload_video_commitsDurableQueuedState_withoutPublishingInsideTransaction() {
		MediaAsset video = mediaRepo.save(MediaAsset.builder()
		                                            .kind(MediaKind.VIDEO)
		                                            .status(MediaStatus.UPLOADING)
		                                            .visibility(MediaVisibility.PUBLIC)
		                                            .ownerType(MediaOwnerType.USER)
		                                            .ownerId(ownerId)
		                                            .mimeType("video/mp4")
		                                            .size(100L)
		                                            .storageKey("quarantine/media/" + UUID.randomUUID() + "/source.mp4")
		                                            .build());
		
		when(mediaPolicy.buildHlsPrefix(eq(video.getId())))
				.thenReturn("media/" + video.getId() + "/hls");
		
		String mutableKey = video.getStorageKey();
		when(storageClient.getObjectMetadata(mutableKey))
				.thenReturn(Optional.of(new StorageObjectMetadata(100L, "video/mp4", "etag")));
		when(storageClient.getObjectMetadata(argThat(key -> key != null
				&& key.startsWith("verified/") && key.contains("/attempts/"))))
				.thenReturn(Optional.of(new StorageObjectMetadata(100L, "video/mp4", "immutable-etag")));
		when(storageClient.getObjectStream(argThat(key -> key != null
				&& key.startsWith("verified/") && key.contains("/attempts/")))).thenReturn(
				new ByteArrayInputStream(new byte[]{0, 0, 0, 12, 'f', 't', 'y', 'p'})
		);
		MediaAsset after = mediaService.completeUpload(ownerId, video.getId());
		
		assertThat(after.getStatus()).isEqualTo(MediaStatus.TRANSCODE_QUEUED);
		assertThat(mediaRepo.findById(video.getId()).orElseThrow().getStatus())
				.isEqualTo(MediaStatus.TRANSCODE_QUEUED);
		assertThat(after.getStorageKey()).startsWith("verified/").contains("/attempts/");
		assertThat(after.getSourceUrl()).isNull();
		assertThat(after.getPlaybackUrl()).isNull();
		verify(storageClient).copyUploadToImmutable(mutableKey, after.getStorageKey(), "etag");
		verify(storageClient, never()).promoteVerifiedObject(
				anyString(), anyString(), anyString(), anyString(), anyString());
		verifyNoInteractions(transcodePublisher);
	}

	@Test
	void rejectedUploadPersistsCleanupIntentUntilPresignedPutExpires() {
		MediaAsset image = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.UPLOADING)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.mimeType("image/png")
				.size(10L)
				.storageKey("quarantine/media/" + UUID.randomUUID() + "/source.png")
				.build());
		String rejectedKey = image.getStorageKey();
		when(storageClient.getObjectMetadata(image.getStorageKey())).thenReturn(Optional.empty());

		assertThatThrownBy(() -> mediaService.completeUpload(ownerId, image.getId()))
				.isInstanceOf(SoundConnectException.class);

		assertThat(mediaRepo.findById(image.getId()).orElseThrow().getStatus())
				.isEqualTo(MediaStatus.CLEANUP_PENDING);
		verify(storageClient, never()).deleteObject(rejectedKey);
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
		
		Page<MediaAsset> all = mediaService.listByOwner(ownerId, MediaOwnerType.USER, ownerId, PageRequest.of(0, 10));
		assertThat(all.getTotalElements()).isEqualTo(2);
		
		Page<MediaAsset> imgs = mediaService.listByOwnerAndKind(
				ownerId, MediaOwnerType.USER, ownerId, MediaKind.IMAGE, PageRequest.of(0, 10));
		assertThat(imgs.getTotalElements()).isEqualTo(1);
		
		// public+ready filtreleri service içinde repo sorgularıyla
		Page<MediaAsset> publicReady = mediaService.listPublicByOwner(
				MediaOwnerType.USER, ownerId, PageRequest.of(0, 10));
		assertThat(publicReady.getTotalElements()).isEqualTo(1);
		assertThat(publicReady.getContent().get(0).getStatus()).isEqualTo(MediaStatus.READY);
		assertThat(publicReady.getContent().get(0).getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
	}

	@Test
	void playbackUrl_returnsOnlyReadyPublicAssets() {
		MediaAsset publicReady = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.AUDIO).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER).ownerId(ownerId)
				.mimeType("audio/mpeg").size(1L).storageKey("public-ready")
				.sourceUrl("https://cdn.test/source.mp3").playbackUrl("https://cdn.test/play.mp3")
				.build());
		MediaAsset privateReady = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.AUDIO).status(MediaStatus.READY).visibility(MediaVisibility.PRIVATE)
				.ownerType(MediaOwnerType.USER).ownerId(ownerId)
				.mimeType("audio/mpeg").size(1L).storageKey("private-ready")
				.sourceUrl("https://cdn.test/private.mp3").playbackUrl("https://cdn.test/private.mp3")
				.build());
		MediaAsset processing = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.VIDEO).status(MediaStatus.PROCESSING).visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER).ownerId(ownerId)
				.mimeType("video/mp4").size(1L).storageKey("processing")
				.sourceUrl("https://cdn.test/source.mp4")
				.build());

		assertThat(mediaService.getPlaybackUrl(publicReady.getId())).isEqualTo(publicReady.getPlaybackUrl());
		assertThat(mediaService.getPlaybackUrlMap(List.of(
				publicReady.getId(), privateReady.getId(), processing.getId())))
				.containsOnly(entry(publicReady.getId(), publicReady.getPlaybackUrl()));
		assertThatThrownBy(() -> mediaService.getPlaybackUrl(privateReady.getId()))
				.isInstanceOf(SoundConnectException.class);
		assertThatThrownBy(() -> mediaService.getPlaybackUrl(processing.getId()))
				.isInstanceOf(SoundConnectException.class);
	}

	@Test
	void displayUrl_prefersThumbnailForVisualMedia_andFallsBackToPlayback() {
		MediaAsset imageWithThumbnail = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.IMAGE).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER).ownerId(ownerId)
				.mimeType("image/jpeg").size(1L).storageKey("media/image/source.jpg")
				.playbackUrl("https://cdn.test/image/source.jpg")
				.thumbnailUrl("https://cdn.test/image/thumbnail.jpg")
				.build());
		MediaAsset legacyImage = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.IMAGE).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER).ownerId(ownerId)
				.mimeType("image/jpeg").size(1L).storageKey("media/legacy/source.jpg")
				.playbackUrl("https://cdn.test/legacy/source.jpg")
				.build());
		MediaAsset audio = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.AUDIO).status(MediaStatus.READY).visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER).ownerId(ownerId)
				.mimeType("audio/mpeg").size(1L).storageKey("media/audio/source.mp3")
				.playbackUrl("https://cdn.test/audio/source.mp3")
				.build());

		assertThat(mediaService.getDisplayUrl(imageWithThumbnail.getId()))
				.isEqualTo("https://cdn.test/image/thumbnail.jpg");
		assertThat(mediaService.getDisplayUrl(legacyImage.getId()))
				.isEqualTo("https://cdn.test/legacy/source.jpg");
		assertThat(mediaService.getDisplayUrl(audio.getId()))
				.isEqualTo("https://cdn.test/audio/source.mp3");
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
	void delete_ownerMatch_commitsDurableIntent_withoutStorageIoInTransaction() {
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
		
		mediaService.delete(asset.getId(), ownerId, MediaOwnerType.USER, ownerId);
		
		assertThat(mediaRepo.findById(asset.getId())).get()
				.extracting(MediaAsset::getStatus)
				.isEqualTo(MediaStatus.DELETION_PENDING);
		verifyNoInteractions(storageClient);
		// image için folder silinmez
	}

	@Test
	void delete_referencedProfileMedia_rejectsBeforeChangingStateOrStorage() {
		MediaAsset asset = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.mimeType("image/png")
				.size(1L)
				.storageKey("media/" + UUID.randomUUID() + "/img.png")
				.sourceUrl("https://cdn.test/img.png")
				.build());
		profileMediaRepository.save(ProfileMedia.builder()
				.profileType(ProfileType.LISTENER)
				.profileId(ownerId)
				.mediaAssetId(asset.getId())
				.role(ProfileMediaRole.GALLERY)
				.build());

		assertThatThrownBy(() -> mediaService.delete(
				asset.getId(), ownerId, MediaOwnerType.USER, ownerId))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.MEDIA_ASSET_IN_USE));

		assertThat(mediaRepo.findById(asset.getId())).get()
				.extracting(MediaAsset::getStatus)
				.isEqualTo(MediaStatus.READY);
		verifyNoInteractions(storageClient);
	}

	@Test
	void delete_withLikesAndThreadedComments_purgesEngagementAndMarksDurableIntent() {
		MediaAsset asset = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.mimeType("image/png")
				.size(1L)
				.storageKey("media/" + UUID.randomUUID() + "/img.png")
				.sourceUrl("https://cdn.test/img.png")
				.playbackUrl("https://cdn.test/img.png")
				.build());
		User engagingUser = saveEngagementUser();
		likeRepository.save(Like.builder()
				.user(engagingUser)
				.targetType(EngagementTargetType.MEDIA)
				.targetId(asset.getId())
				.build());
		Comment root = commentRepository.save(Comment.builder()
				.user(engagingUser)
				.targetType(EngagementTargetType.MEDIA)
				.targetId(asset.getId())
				.text("root")
				.deleted(false)
				.build());
		Comment reply = commentRepository.save(Comment.builder()
				.user(engagingUser)
				.targetType(EngagementTargetType.MEDIA)
				.targetId(asset.getId())
				.text("reply")
				.parentComment(root)
				.deleted(false)
				.build());

		mediaService.delete(asset.getId(), ownerId, MediaOwnerType.USER, ownerId);

		assertThat(mediaRepo.findById(asset.getId())).get()
				.extracting(MediaAsset::getStatus)
				.isEqualTo(MediaStatus.DELETION_PENDING);
		assertThat(likeRepository.countByTargetTypeAndTargetId(
				EngagementTargetType.MEDIA, asset.getId())).isZero();
		assertThat(commentRepository.countByTargetTypeAndTargetId(
				EngagementTargetType.MEDIA, asset.getId())).isZero();
		assertThat(commentRepository.findById(root.getId())).isEmpty();
		assertThat(commentRepository.findById(reply.getId())).isEmpty();
		verifyNoInteractions(storageClient);
	}

	@Test
	void delete_withFirstPartyReference_rejectsWithoutPurgingEngagement() {
		MediaAsset asset = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.mimeType("image/png")
				.size(1L)
				.storageKey("media/" + UUID.randomUUID() + "/img.png")
				.sourceUrl("https://cdn.test/img.png")
				.playbackUrl("https://cdn.test/img.png")
				.build());
		profileMediaRepository.save(ProfileMedia.builder()
				.profileType(ProfileType.LISTENER)
				.profileId(ownerId)
				.mediaAssetId(asset.getId())
				.role(ProfileMediaRole.GALLERY)
				.build());
		User engagingUser = saveEngagementUser();
		likeRepository.save(Like.builder()
				.user(engagingUser)
				.targetType(EngagementTargetType.MEDIA)
				.targetId(asset.getId())
				.build());
		commentRepository.save(Comment.builder()
				.user(engagingUser)
				.targetType(EngagementTargetType.MEDIA)
				.targetId(asset.getId())
				.text("must survive rejected deletion")
				.deleted(false)
				.build());

		assertThatThrownBy(() -> mediaService.delete(
				asset.getId(), ownerId, MediaOwnerType.USER, ownerId))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.MEDIA_ASSET_IN_USE));

		assertThat(likeRepository.countByTargetTypeAndTargetId(
				EngagementTargetType.MEDIA, asset.getId())).isEqualTo(1);
		assertThat(commentRepository.countByTargetTypeAndTargetId(
				EngagementTargetType.MEDIA, asset.getId())).isEqualTo(1);
		assertThat(mediaRepo.findById(asset.getId())).get()
				.extracting(MediaAsset::getStatus)
				.isEqualTo(MediaStatus.READY);
		verifyNoInteractions(storageClient);
	}

	@Test
	void delete_neverCallsStorageSoRollbackCannotResurrectMissingBytes() {
		MediaAsset asset = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.mimeType("image/png")
				.size(1L)
				.storageKey("media/" + UUID.randomUUID() + "/img.png")
				.sourceUrl("https://cdn.test/img.png")
				.build());
		mediaService.delete(asset.getId(), ownerId, MediaOwnerType.USER, ownerId);

		assertThat(mediaRepo.findById(asset.getId())).get()
				.extracting(MediaAsset::getStatus)
				.isEqualTo(MediaStatus.DELETION_PENDING);
		verifyNoInteractions(storageClient);
	}

	@Test
	void delete_outerTransactionRollback_restoresReadyRowAndNeverTouchesStorage() {
		MediaAsset asset = mediaRepo.save(MediaAsset.builder()
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.mimeType("image/png")
				.size(1L)
				.storageKey("media/" + UUID.randomUUID() + "/img.png")
				.sourceUrl("https://cdn.test/img.png")
				.build());
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			mediaService.delete(asset.getId(), ownerId, MediaOwnerType.USER, ownerId);
			verifyNoInteractions(storageClient);
			throw new IllegalStateException("force rollback");
		})).isInstanceOf(IllegalStateException.class);

		assertThat(mediaRepo.findById(asset.getId())).get()
				.extracting(MediaAsset::getStatus)
				.isEqualTo(MediaStatus.READY);
		verifyNoInteractions(storageClient);
	}
	
	private User saveEngagementUser() {
		String suffix = UUID.randomUUID().toString();
		return userRepository.save(User.builder()
				.username("engagement-" + suffix)
				.password("test-password-hash")
				.email("engagement-" + suffix + "@soundconnect.test")
				.status(UserStatus.ACTIVE)
				.emailVerified(true)
				.build());
	}

	@Test
	void delete_ownerMatch_video_onlyMarksDurableIntent() {
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
		mediaService.delete(asset.getId(), ownerId, MediaOwnerType.USER, ownerId);
		
		// 4) DB ve storage doğrulamaları
		assertThat(mediaRepo.findById(asset.getId())).get()
				.extracting(MediaAsset::getStatus)
				.isEqualTo(MediaStatus.DELETION_PENDING);
		verifyNoInteractions(storageClient);
		
		// deleteFolder çağrısında doğru prefix’i kullandığını esnek kontrol et
	}
}
