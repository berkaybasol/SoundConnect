package com.berkayb.soundconnect.modules.media.transcode.validation;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class TranscodeDiskSpaceInspector {
	public long usableBytes(Path workspace) throws IOException {
		return Files.getFileStore(workspace).getUsableSpace();
	}
}
