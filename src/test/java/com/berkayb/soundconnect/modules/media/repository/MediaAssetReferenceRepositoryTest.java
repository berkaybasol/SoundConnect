package com.berkayb.soundconnect.modules.media.repository;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.enums.MediaStatus;
import com.berkayb.soundconnect.modules.media.enums.MediaVisibility;
import com.berkayb.soundconnect.modules.profile.shared.media.entity.ProfileMedia;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileMediaRole;
import com.berkayb.soundconnect.modules.profile.shared.media.enums.ProfileType;
import com.berkayb.soundconnect.modules.promotion.entity.Promotion;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionPlacement;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionStatus;
import com.berkayb.soundconnect.modules.promotion.enums.PromotionType;
import com.berkayb.soundconnect.modules.track.entity.Track;
import com.berkayb.soundconnect.modules.track.enums.TrackOwnerType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableJpaRepositories(basePackages = "com.berkayb.soundconnect")
@EntityScan(basePackages = "com.berkayb.soundconnect")
@TestPropertySource(properties = {
		"spring.datasource.url=jdbc:h2:mem:sc-media-refs-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Tag("repo")
class MediaAssetReferenceRepositoryTest {

	@Autowired
	MediaAssetReferenceRepository repository;

	@Autowired
	TestEntityManager entityManager;

	@Test
	void countsPhysicalAndLogicalReferences() {
		UUID ownerId = UUID.randomUUID();
		MediaAsset asset = entityManager.persist(MediaAsset.builder()
				.kind(MediaKind.IMAGE)
				.status(MediaStatus.READY)
				.visibility(MediaVisibility.PUBLIC)
				.ownerType(MediaOwnerType.USER)
				.ownerId(ownerId)
				.mimeType("image/jpeg")
				.size(123L)
				.storageKey("media/reference-test/source.jpg")
				.playbackUrl("https://cdn.test/source.jpg")
				.build());

		entityManager.persist(Promotion.builder()
				.type(PromotionType.FEATURED_CONTENT)
				.placement(PromotionPlacement.VENUE_MANAGEMENT_PANEL)
				.status(PromotionStatus.ACTIVE)
				.title("Referenced promotion")
				.mediaAsset(asset)
				.build());
		entityManager.persist(Track.builder()
				.mediaAssetId(asset.getId())
				.ownerType(TrackOwnerType.MUSICIAN_PROFILE)
				.ownerId(ownerId)
				.title("Referenced track")
				.build());
		entityManager.persist(ProfileMedia.builder()
				.profileType(ProfileType.MUSICIAN)
				.profileId(ownerId)
				.mediaAssetId(asset.getId())
				.role(ProfileMediaRole.GALLERY)
				.build());
		entityManager.flush();

		assertThat(repository.countPromotionReferences(asset.getId())).isEqualTo(1);
		assertThat(repository.countTrackReferences(asset.getId())).isEqualTo(1);
		assertThat(repository.countProfileMediaReferences(asset.getId())).isEqualTo(1);
		assertThat(repository.countPromotionReferences(UUID.randomUUID())).isZero();
	}
}
