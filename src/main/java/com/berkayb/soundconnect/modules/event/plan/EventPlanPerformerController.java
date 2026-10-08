package com.berkayb.soundconnect.modules.event.plan;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.event.enums.PerformerType;
import com.berkayb.soundconnect.shared.response.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/user/event-plans") @PreAuthorize("hasRole('MUSICIAN')")
public class EventPlanPerformerController {
    private final EventPlanService service;
    private final EventPlanRateGuard guard;
    @GetMapping
    public ResponseEntity<BaseResponse<PageResponse<EventPlanResponse>>> list(@AuthenticationPrincipal UserDetailsImpl principal,
            @RequestParam PerformerType targetType,@RequestParam UUID targetId,
            @RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size) {
        return EventPlanOwnerController.response(service.getPerformerPlans(principal.getUser().getId(),targetType,targetId,page,size));
    }
    @GetMapping("/{planId}")
    public ResponseEntity<BaseResponse<EventPlanResponse>> get(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID planId) {
        return EventPlanOwnerController.response(service.getPerformer(principal.getUser().getId(),planId));
    }
    @PostMapping("/{planId}/decision")
    public ResponseEntity<BaseResponse<EventPlanResponse>> decide(@AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID planId,@RequestBody EventPlanDecisionRequest request) {
        UUID actor=principal.getUser().getId();guard.check(actor);return EventPlanOwnerController.response(service.decide(actor,planId,request));
    }
}
