package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.admin.security.AdminPanelReadAccess;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class VenueSuggestionAdminController {
    private final VenueSuggestionAdminQuery suggestions;

    @GetMapping("/api/v1/admin/venue-suggestions")
    @PreAuthorize(AdminPanelReadAccess.PANEL + " and hasAuthority('MANAGE_VENUES')")
    public ResponseEntity<BaseResponse<PageResponse<VenueSuggestionAdminQuery.Row>>> list(
            @AuthenticationPrincipal UserDetailsImpl actor, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var data = suggestions.list(actor, page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(BaseResponse.<PageResponse<VenueSuggestionAdminQuery.Row>>builder()
                        .success(true).code(200).data(data).build());
    }
}
