package com.berkayb.soundconnect.modules.setlistcreator.pdf;

import java.io.ByteArrayInputStream;
import java.util.UUID;

public interface SetlistPdfService {
	
	ByteArrayInputStream exportSetlistPdf(UUID setlistId);
}