// src/test/java/com/berkayb/soundconnect/modules/media/repository/MediaAssetRepositoryTest.java
package com.berkayb.soundconnect.modules.media.repository;

import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
		"spring.datasource.url=jdbc:h2:mem:sc-media-${random.uuid};MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
		"spring.jpa.hibernate.ddl-auto=create-drop"
})
@Tag("repo")
class MediaAssetRepositoryTest {
	
	@org.springframework.beans.factory.annotation.Autowired
	MediaAssetRepository repo;
	
	private MediaAsset save(UUID ownerId, MediaKind kind, MediaVisibility vis, MediaStatus st) {
		return repo.save(MediaAsset.builder()
		                           .kind(kind)
		                           .visibility(vis)
		                           .status(st)
		                           .ownerType(MediaOwnerType.USER)
		                           .ownerId(ownerId)
		                           .mimeType(switch (kind) {
			                           case IMAGE -> "image/png";
			                           case AUDIO -> "audio/mpeg";
			                           case VIDEO -> "video/mp4";
		                           })
		                           .size(123L)
		                           .storageKey("media/" + UUID.randomUUID() + "/source")
		                           .sourceUrl("https://cdn.test/" + UUID.randomUUID())
		                           .build());
	}
	
	@Test
	void findByOwnerTypeAndOwnerId_should_returnOwnerAssets_paged() {
		UUID owner = UUID.randomUUID();
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner, MediaKind.VIDEO, MediaVisibility.PRIVATE, MediaStatus.UPLOADING);
		// başka owner
		save(UUID.randomUUID(), MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		
		Page<MediaAsset> page = repo.findByOwnerTypeAndOwnerId(MediaOwnerType.USER, owner, PageRequest.of(0, 10));
		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(page.getContent()).allMatch(a -> owner.equals(a.getOwnerId()));
	}
	
	@Test
	void findByOwnerTypeAndOwnerIdAndKind_should_filterByKind() {
		UUID owner = UUID.randomUUID();
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.READY);
		
		Page<MediaAsset> page = repo.findByOwnerTypeAndOwnerIdAndKind(
				MediaOwnerType.USER, owner, MediaKind.IMAGE, PageRequest.of(0, 10));
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent().get(0).getKind()).isEqualTo(MediaKind.IMAGE);
	}
	
	@Test
	void findByOwnerTypeAndOwnerIdAndVisibilityAndStatus_should_returnOnlyPublicReadyOfOwner() {
		UUID owner = UUID.randomUUID();
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner, MediaKind.IMAGE, MediaVisibility.PRIVATE, MediaStatus.READY);
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.PROCESSING);
		
		Page<MediaAsset> page = repo.findByOwnerTypeAndOwnerIdAndVisibilityAndStatus(
				MediaOwnerType.USER, owner, MediaVisibility.PUBLIC, MediaStatus.READY, PageRequest.of(0, 10));
		
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent().get(0).getVisibility()).isEqualTo(MediaVisibility.PUBLIC);
		assertThat(page.getContent().get(0).getStatus()).isEqualTo(MediaStatus.READY);
	}
	
	@Test
	void findByOwnerTypeAndOwnerIdAndKindAndVisibilityAndStatus_should_filterAll() {
		UUID owner = UUID.randomUUID();
		save(owner, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.PROCESSING);
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		
		Page<MediaAsset> page = repo.findByOwnerTypeAndOwnerIdAndKindAndVisibilityAndStatus(
				MediaOwnerType.USER, owner, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.READY, PageRequest.of(0, 10));
		
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent().get(0).getKind()).isEqualTo(MediaKind.VIDEO);
		assertThat(page.getContent().get(0).getStatus()).isEqualTo(MediaStatus.READY);
	}
	
	@Test
	void findByVisibilityAndStatus_should_returnSystemWidePublicReady() {
		UUID owner1 = UUID.randomUUID();
		UUID owner2 = UUID.randomUUID();
		save(owner1, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner1, MediaKind.IMAGE, MediaVisibility.PRIVATE, MediaStatus.READY);
		save(owner2, MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.READY);
		
		Page<MediaAsset> page = repo.findByVisibilityAndStatus(
				MediaVisibility.PUBLIC, MediaStatus.READY, PageRequest.of(0, 10));
		
		assertThat(page.getTotalElements()).isEqualTo(2);
		assertThat(page.getContent()).allMatch(a ->
				                                       a.getVisibility() == MediaVisibility.PUBLIC && a.getStatus() == MediaStatus.READY);
	}
	
	@Test
	void findByVisibilityAndStatusAndKind_should_filterByKindSystemWide() {
		save(UUID.randomUUID(), MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(UUID.randomUUID(), MediaKind.VIDEO, MediaVisibility.PUBLIC, MediaStatus.READY);
		
		Page<MediaAsset> page = repo.findByVisibilityAndStatusAndKind(
				MediaVisibility.PUBLIC, MediaStatus.READY, MediaKind.IMAGE, PageRequest.of(0, 10));
		
		assertThat(page.getTotalElements()).isEqualTo(1);
		assertThat(page.getContent().get(0).getKind()).isEqualTo(MediaKind.IMAGE);
	}
	
	@Test
	void countByOwnerTypeAndOwnerId_should_returnCount() {
		UUID owner = UUID.randomUUID();
		save(owner, MediaKind.IMAGE, MediaVisibility.PUBLIC, MediaStatus.READY);
		save(owner, MediaKind.AUDIO, MediaVisibility.PRIVATE, MediaStatus.UPLOADING);
		
		long count = repo.countByOwnerTypeAndOwnerId(MediaOwnerType.USER, owner);
		assertThat(count).isEqualTo(2);
	}
}