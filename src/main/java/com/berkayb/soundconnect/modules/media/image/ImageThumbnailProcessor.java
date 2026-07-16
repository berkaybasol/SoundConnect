package com.berkayb.soundconnect.modules.media.image;

import java.io.IOException;
import java.nio.file.Path;

public interface ImageThumbnailProcessor {
	ProcessedImageThumbnail createThumbnail(Path source, Path target) throws IOException, InterruptedException;
}
