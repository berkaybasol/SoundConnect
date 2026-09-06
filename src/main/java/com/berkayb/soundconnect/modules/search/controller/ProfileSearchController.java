package com.berkayb.soundconnect.modules.search.controller;

import com.berkayb.soundconnect.modules.search.dto.ProfileSearchItemDto;
import com.berkayb.soundconnect.modules.search.enums.ProfileSearchType;
import com.berkayb.soundconnect.modules.search.service.ProfileSearchService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/public/search")
@RequiredArgsConstructor
@Tag(name = "PUBLIC / Search", description = "Global discovery search endpoints")
public class ProfileSearchController {
	private final ProfileSearchService profileSearchService;

	@GetMapping("/profiles")
	public ResponseEntity<BaseResponse<List<ProfileSearchItemDto>>> searchProfiles(
			@RequestParam(name = "q", required = false) String query,
			@RequestParam(name = "limit", defaultValue = "15") int limit,
			@RequestParam(name = "types", required = false) Set<ProfileSearchType> types
	) {
		return ResponseEntity.ok(
				BaseResponse.<List<ProfileSearchItemDto>>builder()
				            .success(true)
				            .code(200)
				            .message("Profile search results fetched")
				            .data(profileSearchService.searchProfiles(query, limit, types))
				            .build()
		);
	}
}
