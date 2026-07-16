package com.berkayb.soundconnect.modules.engagement.service;

import com.berkayb.soundconnect.modules.engagement.enums.EngagementTargetType;
import com.berkayb.soundconnect.modules.event.repository.EventRepository;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.media.repository.MediaAssetRepository;
import com.berkayb.soundconnect.modules.overthinking.repository.OverthinkingPostRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EngagementTargetValidatorMediaLockTest {

	@Mock OverthinkingPostRepository overthinkingPostRepository;
	@Mock MediaAssetRepository mediaAssetRepository;
	@Mock EventRepository eventRepository;
	@InjectMocks EngagementTargetValidatorImpl validator;

	@Test
	void mediaValidationUsesDeletionCompatibleRowLock() {
		UUID assetId = UUID.randomUUID();
		MediaAsset ready = MediaAsset.builder()
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.playbackUrl("https://cdn.test/media.jpg")
				.build();
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(ready));

		assertThatCode(() -> validator.validateExists(EngagementTargetType.MEDIA, assetId))
				.doesNotThrowAnyException();

		verify(mediaAssetRepository).findByIdForUpdate(assetId);
		verify(mediaAssetRepository, never()).existsById(assetId);
	}

	@Test
	void mediaValidationRejectsDeletionPendingAssetAfterLock() {
		UUID assetId = UUID.randomUUID();
		MediaAsset pending = MediaAsset.builder()
				.status(MediaStatus.DELETION_PENDING)
				.visibility(MediaVisibility.PUBLIC)
				.playbackUrl("https://cdn.test/media.jpg")
				.build();
		when(mediaAssetRepository.findByIdForUpdate(assetId)).thenReturn(Optional.of(pending));

		assertThatThrownBy(() -> validator.validateExists(EngagementTargetType.MEDIA, assetId))
				.isInstanceOfSatisfying(SoundConnectException.class, exception ->
						assertThat(exception.getErrorType()).isEqualTo(ErrorType.ENGAGEMENT_NOT_FOUND));
	}

	@Test
	void validatorRequiresCallerTransactionSoLockCannotEscapeEarly() throws Exception {
		Method method = EngagementTargetValidatorImpl.class.getMethod(
				"validateExists", EngagementTargetType.class, UUID.class);
		Transactional transactional = method.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
	}
}
