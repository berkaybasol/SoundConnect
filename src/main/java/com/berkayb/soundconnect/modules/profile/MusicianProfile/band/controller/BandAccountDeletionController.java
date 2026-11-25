package com.berkayb.soundconnect.modules.profile.MusicianProfile.band.controller;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.dto.response.BandDeletionOption;
import com.berkayb.soundconnect.modules.profile.MusicianProfile.band.service.BandAccountLifecycle;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping(EndPoints.Band.USER_BASE)
@RequiredArgsConstructor
public class BandAccountDeletionController {
    private final BandAccountLifecycle lifecycle;

    @GetMapping("/account-deletion-options")
    @PreAuthorize("hasRole('MUSICIAN') and principal.enabled")
    public ResponseEntity<BaseResponse<List<BandDeletionOption>>> deletionOptions(
            @AuthenticationPrincipal UserDetailsImpl principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                BaseResponse.<List<BandDeletionOption>>builder().success(true).code(200)
                        .data(lifecycle.deletionOptions(principal.getUser().getId())).build());
    }
}
