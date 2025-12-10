package com.berkayb.soundconnect.modules.setlistcreator.pdf;

import com.berkayb.soundconnect.modules.setlistcreator.entity.Setlist;
import com.berkayb.soundconnect.modules.setlistcreator.entity.SetlistItem;
import com.berkayb.soundconnect.modules.setlistcreator.entity.SetlistSet;
import com.berkayb.soundconnect.modules.setlistcreator.repository.SetlistRepository;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SetlistPdfServiceImpl implements SetlistPdfService{
	
	private final SetlistRepository setlistRepository;
	
	@Override
	public ByteArrayInputStream exportSetlistPdf(UUID setlistId) {
		log.info("Generating PDF for setlist {}", setlistId);
		
		Setlist setlist = setlistRepository.findById(setlistId)
				.orElseThrow(() -> new SoundConnectException(ErrorType.SETLIST_NOT_FOUND));
		
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			Document document = new Document(PageSize.A4);
			PdfWriter.getInstance(document, out);
			
			document.open();
			
			// title
			Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
			Paragraph title = new Paragraph(setlist.getName(), titleFont);
			title.setAlignment(Element.ALIGN_CENTER);
			title.setSpacingAfter(20);
			document.add(title);
			
			// setler
			for (SetlistSet set : setlist.getSets()) {
				
				Font setFont = FontFactory.getFont(FontFactory.HELVETICA, 14);
				Paragraph setTitle = new Paragraph(
						set.getTitle() +
								(set.getDuration() != null ? " (" + set.getDuration() + ")" : ""),
						setFont
				);
				setTitle.setSpacingBefore(15);
				setTitle.setSpacingAfter(10);
				document.add(setTitle);
				
				PdfPTable table = new PdfPTable(3);
				table.setWidthPercentage(100);
				table.setSpacingAfter(10);
				table.setWidths(new int[]{1,6,2});
				
				table.addCell("No");
				table.addCell("Şarkı");
				table.addCell("Ton");
				
				for (SetlistItem item : set.getItems()) {
					table.addCell(item.getOrderNumber().toString());
					table.addCell(item.getArtistName() + " - " + item.getSongName());
					table.addCell(item.getKey().name());
				}
				document.add(table);
			}
			document.close();
			
			return new ByteArrayInputStream(out.toByteArray());
		} catch (Exception e) {
			log.error("PDF generation failed", e);
			throw new SoundConnectException(ErrorType.INTERNAL_ERROR,"PDF olusturulurken hata olustu");
		}
	}
}