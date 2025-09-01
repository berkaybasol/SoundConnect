// modules/media/controller/PublicMediaController.java
package com.berkayb.soundconnect.modules.media.controller.user;

import com.berkayb.soundconnect.modules.media.dto.response.MediaResponseDto;
import com.berkayb.soundconnect.modules.media.enums.MediaKind;
import com.berkayb.soundconnect.modules.media.enums.MediaOwnerType;
import com.berkayb.soundconnect.modules.media.mapper.MediaAssetMapper;
import com.berkayb.soundconnect.modules.media.service.MediaAssetService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.Media.PUBLIC_BASE;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Media.PUBLIC_BY_OWNER;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Media.PUBLIC_BY_OWNER_AND_KIND;

@RestController
@RequestMapping(PUBLIC_BASE) // /api/v1/public/media
@RequiredArgsConstructor
@Tag(name = "MEDIA / PUBLIC", description = "Public media listing")
public class PublicMediaController {
	
	private final MediaAssetService mediaAssetService;
	private final MediaAssetMapper mapper;
	
	// GET /api/v1/public/media/owner/{ownerType}/{ownerId}
	@GetMapping(PUBLIC_BY_OWNER)
	public BaseResponse<Page<MediaResponseDto>> listPublicByOwner(
			@PathVariable MediaOwnerType ownerType,
			@PathVariable UUID ownerId,
			@ParameterObject
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
			Pageable pageable
	) {
		var page = mediaAssetService.listPublicByOwner(ownerType, ownerId, pageable).map(mapper::toDto);
		return BaseResponse.<Page<MediaResponseDto>>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Public media fetched")
		                   .data(page)
		                   .build();
	}
	
	// GET /api/v1/public/media/owner/{ownerType}/{ownerId}/kind/{kind}
	@GetMapping(PUBLIC_BY_OWNER_AND_KIND)
	public BaseResponse<Page<MediaResponseDto>> listPublicByOwnerAndKind(
			@PathVariable MediaOwnerType ownerType,
			@PathVariable UUID ownerId,
			@PathVariable MediaKind kind,
			@ParameterObject
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
			Pageable pageable
	) {
		var page = mediaAssetService.listPublicByOwnerAndKind(ownerType, ownerId, kind, pageable).map(mapper::toDto);
		return BaseResponse.<Page<MediaResponseDto>>builder()
		                   .success(true)
		                   .code(200)
		                   .message("Public media by kind fetched")
		                   .data(page)
		                   .build();
	}
}