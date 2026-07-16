package com.berkayb.soundconnect.modules.media.image;

import com.berkayb.soundconnect.modules.media.storage.StorageClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImageThumbnailServiceTest {

	@Mock StorageClient storageClient;
	@Mock ImageThumbnailProcessor thumbnailProcessor;

	@Test
	void generateAndStore_usesDeterministicPublicKeyAndPersistsDimensions() throws Exception {
		UUID assetId = UUID.randomUUID();
		String sourceKey = "verified/media/" + assetId + "/source.jpg";
		String thumbnailKey = "media/" + assetId + "/thumbnail.jpg";
		when(storageClient.publicUrl(thumbnailKey)).thenReturn("https://cdn.test/" + thumbnailKey);
		when(thumbnailProcessor.createThumbnail(any(Path.class), any(Path.class)))
				.thenReturn(new ProcessedImageThumbnail(2296, 4080, 540, 960));
		ImageThumbnailService service = new ImageThumbnailService(
				storageClient, thumbnailProcessor, new MediaImageVariantProperties());

		ImageThumbnailResult result = service.generateAndStoreDetached(assetId, sourceKey);

		assertThat(result.thumbnailKey()).isEqualTo(thumbnailKey);
		assertThat(result.thumbnailUrl()).isEqualTo("https://cdn.test/" + thumbnailKey);
		assertThat(result.sourceWidth()).isEqualTo(2296);
		assertThat(result.sourceHeight()).isEqualTo(4080);
		assertThat(result.thumbnailWidth()).isEqualTo(540);
		assertThat(result.thumbnailHeight()).isEqualTo(960);
		verify(storageClient).downloadToFile(
				any(String.class), any(Path.class),
				org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(30)));
		ArgumentCaptor<Path> fileCaptor = ArgumentCaptor.forClass(Path.class);
		verify(storageClient).putFile(
				fileCaptor.capture(), org.mockito.ArgumentMatchers.eq(thumbnailKey),
				org.mockito.ArgumentMatchers.eq("image/jpeg"),
				org.mockito.ArgumentMatchers.eq(
						"public, max-age=300, s-maxage=3600, stale-while-revalidate=60"));
		assertThat(fileCaptor.getValue().getFileName().toString()).isEqualTo("thumbnail.jpg");
	}

	@Test
	void thumbnailKeyFor_rejectsProtectedSource() {
		assertThatThrownBy(() -> ImageThumbnailService.thumbnailKeyFor(
				"protected/media/id/source.jpg"))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
