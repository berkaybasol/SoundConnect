package com.berkayb.soundconnect.modules.setlistcreator.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistCreateRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistItemRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.request.SetlistSetRequestDto;
import com.berkayb.soundconnect.modules.setlistcreator.dto.response.SetlistResponseDto;
import com.berkayb.soundconnect.modules.setlistcreator.pdf.SetlistPdfService;
import com.berkayb.soundconnect.modules.setlistcreator.service.SetlistService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;


import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Setlist.*;
@RestController
@RequestMapping(BASE)
@RequiredArgsConstructor
@Slf4j
public class SetlistController {
	
	private final SetlistService setlistService;
	private final SetlistPdfService setlistPdfService;
	
	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping(CREATE)
	@Operation(summary = "Setlist olustur")
	public ResponseEntity<BaseResponse<SetlistResponseDto>> createSetlist(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@RequestBody @Valid SetlistCreateRequestDto request
	) {
		log.info("Creating setlist");
		
		SetlistResponseDto response = setlistService.createSetlist(userDetails.getUser().getId(), request);
		
		return ResponseEntity.ok(
				BaseResponse.<SetlistResponseDto>builder()
						.success(true)
						.message("Setlist basariyla olusturuldu")
						.code(200)
						.data(response)
						.build()
		);
	}
	
	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping(ADD_SET)
	@Operation(summary = "set olustur")
	public ResponseEntity<BaseResponse<SetlistResponseDto>> addSetToSetlist(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID setlistId,
			@RequestBody @Valid SetlistSetRequestDto request
			) {
		log.info("Adding set to setlist {}", setlistId);
		
		SetlistResponseDto response = setlistService.addSetToSetlist(userDetails.getUser().getId(), setlistId, request);
		
		return ResponseEntity.ok(
				BaseResponse.<SetlistResponseDto>builder()
						.success(true)
						.message("Set basariyla setliste eklendi")
						.code(200)
						.data(response)
						.build()
		);
	}
	
	
	@PreAuthorize("hasRole('MUSICIAN')")
	@PostMapping(ADD_ITEM)
	@Operation(summary = "Sarki ekle")
	public ResponseEntity<BaseResponse<SetlistResponseDto>> addItemToSet(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID setId,
			@RequestBody @Valid SetlistItemRequestDto request
	) {
		log.info("Adding item to set {}", setId);
		
		SetlistResponseDto response =
				setlistService.addItemToSet(userDetails.getUser().getId(), setId, request);
		
		return ResponseEntity.ok(
				BaseResponse.<SetlistResponseDto>builder()
				            .success(true)
				            .message("Şarkı başarıyla eklendi")
				            .code(200)
				            .data(response)
				            .build());
	}
	
	@PreAuthorize("hasRole('MUSICIAN')")
	@GetMapping(BY_ID)
	@Operation(summary = "Id'ye gore setlist getir")
	public ResponseEntity<BaseResponse<SetlistResponseDto>> getSetlistDetail(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID setlistId
	) {
		log.info("Getting setlist detail {}", setlistId);
		
		SetlistResponseDto response =
				setlistService.getSetlistDetail(userDetails.getUser().getId(), setlistId);
		
		return ResponseEntity.ok(
				BaseResponse.<SetlistResponseDto>builder()
						.success(true)
						.message("Setlist getirildi")
						.code(200)
						.data(response)
						.build()
		);
	}
	
	@PreAuthorize("hasRole('MUSICIAN')")
	@DeleteMapping(DELETE)
	@Operation(summary = "Setlist sil")
	public ResponseEntity<BaseResponse<Void>> deleteSetlist(
			@AuthenticationPrincipal UserDetailsImpl userDetails,
			@PathVariable UUID setlistId
	) {
		log.info("Deleting setlist {}", setlistId);
		
		setlistService.deleteSetlist(userDetails.getUser().getId(), setlistId);
		
		return ResponseEntity.ok(
				BaseResponse.<Void>builder()
						.success(true)
						.message("Setlist silindi")
						.code(200)
						.build()
		);
	}
	
	@GetMapping(value = PDF_EXPORT,
	produces = "application/pdf")
	@Operation(summary = "Setlist PDF indir")
	public ResponseEntity<byte[]> exportSetlistPdf(
			@PathVariable UUID setlistId
	) {
		byte[] pdfBytes = setlistPdfService
				.exportSetlistPdf(setlistId)
				.readAllBytes();
		
		return ResponseEntity.ok()
		                     .header("Content-Disposition", "inline; filename=setlist.pdf")
		                     .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
		                     .body(pdfBytes);
	}
}
