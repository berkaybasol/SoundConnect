package com.berkayb.soundconnect.modules.notification.push;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/notifications/push")
@PreAuthorize("!hasRole('LISTENER') and hasAnyRole('OWNER','ADMIN')")
@RequiredArgsConstructor
@Validated
@ConditionalOnProperty(name="app.notification.push.enabled",havingValue="true")
public class PushAdminController {
    private final PushOperations operations;
    @GetMapping("/summary") public BaseResponse<PushOperations.Summary> summary() { return response(operations.summary()); }
    @GetMapping("/failed") public BaseResponse<List<PushOperations.Job>> failed(@RequestParam(defaultValue="25") @Min(1) @Max(100) int limit) {
        return response(operations.failed(limit));
    }
    @PostMapping("/deliveries/{id}/retry") public BaseResponse<Map<String,Boolean>> retry(@PathVariable UUID id,@AuthenticationPrincipal UserDetailsImpl user) {
        return response(Map.of("scheduled",operations.retry(id,user.getId())));
    }
    private static <T> BaseResponse<T> response(T data) {
        return BaseResponse.<T>builder().success(true).code(200).message("Push operations").data(data).build();
    }
}
