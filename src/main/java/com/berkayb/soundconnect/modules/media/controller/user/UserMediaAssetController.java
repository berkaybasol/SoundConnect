package com.berkayb.soundconnect.modules.media.controller.user;

import com.berkayb.soundconnect.modules.media.dto.request.CompleteUploadRequestDto;
import com.berkayb.soundconnect.modules.media.dto.request.UploadInitRequestDto;
import com.berkayb.soundconnect.modules.media.dto.response.MediaResponseDto;
import com.berkayb.soundconnect.modules.media.dto.response.UploadInitResultResponseDto;
import com.berkayb.soundconnect.modules.media.entity.MediaAsset;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.mapper.MediaAssetMapper;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Media.*;

@RestController
@RequiredArgsConstructor
@RequestMapping(USER_BASE) // sadece USER endpointleri
@Tag(name = "MEDIA / USERS", description = "Users Media Management")
public class UserMediaAssetController {
	
	private final MediaAssetService mediaAssetService;
	private final MediaAssetMapper mapper;
	
	@PostMapping(INIT_UPLOAD)
	public BaseResponse<UploadInitResultResponseDto> initUpload(@Valid @RequestBody UploadInitRequestDto dto) {
		var result = mediaAssetService.initUpload(
				dto.ownerType(),
				dto.ownerId(),
				dto.kind(),
				dto.visibility(),
				dto.mimeType(),
				dto.sizeBytes(),
				dto.originalFileName()
		);
		return BaseResponse.<UploadInitResultResponseDto>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Upload initialized")
		                   .data(result)
		                   .build();
	}
	
	@PostMapping(COMPLETE_UPLOAD)
	public BaseResponse<MediaResponseDto> completeUpload(@Valid @RequestBody CompleteUploadRequestDto dto) {
		MediaAsset asset = mediaAssetService.completeUpload(dto.assetId());
		return BaseResponse.<MediaResponseDto>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Upload completed.")
		                   .data(mapper.toDto(asset))
		                   .build();
	}
	
	@GetMapping(LIST_BY_OWNER)
	public BaseResponse<Page<MediaResponseDto>> listByOwner(
			@PathVariable MediaOwnerType ownerType,
			@PathVariable UUID ownerId,
			Pageable pageable
	) {
		var page = mediaAssetService.listByOwner(ownerType, ownerId, pageable).map(mapper::toDto);
		return BaseResponse.<Page<MediaResponseDto>>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Media list fetched")
		                   .data(page)
		                   .build();
	}
	
	@GetMapping(LIST_BY_OWNER_AND_KIND)
	public BaseResponse<Page<MediaResponseDto>> listByOwnerAndKind(
			@PathVariable MediaOwnerType ownerType,
			@PathVariable UUID ownerId,
			@PathVariable MediaKind kind,
			Pageable pageable
	) {
		var page = mediaAssetService.listByOwnerAndKind(ownerType, ownerId, kind, pageable).map(mapper::toDto);
		return BaseResponse.<Page<MediaResponseDto>>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Media list by kind fetched.")
		                   .data(page)
		                   .build();
	}
	
	@DeleteMapping(DELETE)
	public BaseResponse<Void> delete(
			@PathVariable UUID assetId,
			@RequestParam UUID actingUserId,
			@RequestParam MediaOwnerType actingAsType,
			@RequestParam UUID actingAsId
	) {
		mediaAssetService.delete(assetId, actingUserId, actingAsType, actingAsId);
		return BaseResponse.<Void>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Media deleted")
		                   .build();
	}
}