package com.berkayb.soundconnect.modules.media.recovery;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaStreamingProtocol;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.shared.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.transaction.TestTransaction;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackages = "com.berkayb.soundconnect")
@Import({JpaAuditingConfig.class, MediaPublicPromotionRecoveryRepository.class})
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-public-recovery-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
class MediaPublicPromotionRecoveryRepositoryIT {

	@Autowired TestEntityManager entityManager;
	@Autowired MediaPublicPromotionRecoveryRepository repository;

	@Test
	void actualQueryReturnsOnlyLivePublicProgressiveImageAndAudioRows() {
		MediaAsset image = persist(MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		MediaAsset audio = persist(MediaKind.AUDIO, MediaVisibility.PUBLIC, MediaStatus.READY);
		persist(MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.READY);
		persist(MediaKind.IMAGE, MediaVisibility.PRIVATE, MediaStatus.READY);
		persist(MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.UPLOADING);
		entityManager.flush();
		UUID imageId = image.getId();
		UUID audioId = audio.getId();
		TestTransaction.flagForCommit();
		TestTransaction.end();

		var targets = repository.findReadyBatch(0, 10);

		assertThat(targets)
				.extracting(MediaPublicPromotionRecoveryTarget::assetId)
				.containsExactlyInAnyOrder(imageId, audioId);
	}

	private MediaAsset persist(
			MediaKind kind,
			MediaVisibility visibility,
			MediaStatus status
	) {
		UUID assetId = UUID.randomUUID();
		return entityManager.persist(MediaAsset.builder()
				.kind(kind)
				.status(status)
				.visibility(visibility)
				.ownerType(MediaOwnerType.USER)
				.ownerId(UUID.randomUUID())
				.storageKey("media/" + assetId + "/source")
				.mimeType(switch (kind) {
					case IMAGE -> "image/jpeg";
					case AUDIO -> "audio/mpeg";
					case VIDEO -> "video/mp4";
				})
				.size(100L)
				.streamingProtocol(kind == MediaKind.VIDEO
						? MediaStreamingProtocol.HLS
						: MediaStreamingProtocol.PROGRESSIVE)
				.build());
	}
}
