package com.berkayb.soundconnect.modules.application.admin;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.constant.EndPoints;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.berkayb.soundconnect.shared.response.PageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static com.berkayb.soundconnect.modules.application.admin.PendingApplicationQuery.Kind.STUDIO;
import static com.berkayb.soundconnect.modules.application.admin.PendingApplicationQuery.Kind.VENUE;

@RestController
@RequiredArgsConstructor
public class PendingApplicationAdminController {
    private static final String PANEL = "hasAuthority('ADMIN_PANEL_ACCESS') and !hasRole('LISTENER')";
    private static final String READ_VENUE = PANEL + " and hasAuthority('MANAGE_VENUE_APPLICATIONS')";
    private static final String READ_STUDIO = PANEL + " and hasAuthority('MANAGE_STUDIO_APPLICATIONS')";
    private static final String VENUES = EndPoints.VenueApplication.ADMIN_BASE + "/pending";
    private static final String STUDIOS = EndPoints.StudioApplication.ADMIN_BASE + "/pending";
    private final PendingApplicationQuery applications;

    @GetMapping(VENUES)
    @PreAuthorize(READ_VENUE)
    public ResponseEntity<BaseResponse<PageResponse<PendingApplicationRow>>> venues(
            @AuthenticationPrincipal UserDetailsImpl actor,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return response(applications.list(actor, VENUE, page, size));
    }

    @GetMapping(STUDIOS)
    @PreAuthorize(READ_STUDIO)
    public ResponseEntity<BaseResponse<PageResponse<PendingApplicationRow>>> studios(
            @AuthenticationPrincipal UserDetailsImpl actor,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return response(applications.list(actor, STUDIO, page, size));
    }

    @GetMapping(VENUES + "/{id}")
    @PreAuthorize(READ_VENUE)
    public ResponseEntity<BaseResponse<PendingApplicationRow>> venue(
            @AuthenticationPrincipal UserDetailsImpl actor, @PathVariable UUID id) {
        return response(applications.detail(actor, VENUE, id));
    }

    @GetMapping(STUDIOS + "/{id}")
    @PreAuthorize(READ_STUDIO)
    public ResponseEntity<BaseResponse<PendingApplicationRow>> studio(
            @AuthenticationPrincipal UserDetailsImpl actor, @PathVariable UUID id) {
        return response(applications.detail(actor, STUDIO, id));
    }

    private static <T> ResponseEntity<BaseResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                BaseResponse.<T>builder().success(true).code(200).data(data).build());
    }
}
