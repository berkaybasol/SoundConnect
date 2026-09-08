package com.berkayb.soundconnect.modules.profile.VenueProfile.controller.user;

import com.berkayb.soundconnect.modules.profile.VenueProfile.dto.response.VenueActiveArtistDto;
import com.berkayb.soundconnect.modules.profile.VenueProfile.enums.VenueActiveArtistType;
import com.berkayb.soundconnect.modules.profile.VenueProfile.service.VenueActiveArtistService;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.berkayb.soundconnect.shared.constant.EndPoints.VenueProfile.PUBLIC_BASE;

@RestController
@RequestMapping(PUBLIC_BASE)
@RequiredArgsConstructor
@Tag(name = "PUBLIC / Venue Profile")
public class VenueActiveArtistController {
    private final VenueActiveArtistService service;

    @GetMapping("/{venueId}/active-artists")
    public ResponseEntity<BaseResponse<PageResponse<VenueActiveArtistDto>>> list(
            @PathVariable UUID venueId,
            @RequestParam(defaultValue = "MUSICIAN") VenueActiveArtistType type,
            @RequestParam(name = "q", required = false) @Size(max = 100) String query,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ResponseEntity.ok(BaseResponse.<PageResponse<VenueActiveArtistDto>>builder()
                .success(true).code(200).message("Aktif sanatçılar getirildi.")
                .data(service.list(venueId, type, query, page, size)).build());
    }
}
