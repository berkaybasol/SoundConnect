package com.berkayb.soundconnect.modules.notification.controller.user;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.notification.dto.response.NotificationResponseDto;
import com.berkayb.soundconnect.modules.notification.support.CollabNotificationIdentity;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
import static com.berkayb.soundconnect.shared.constant.EndPoints.Notification.USER_BASE;

@RestController
@RequestMapping(USER_BASE)
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class CollabNotificationController {
    private final NamedParameterJdbcTemplate jdbc;
    @GetMapping("/{notificationId}/collab-target")
    @Transactional(readOnly=true)
    public BaseResponse<NotificationResponseDto> target(@AuthenticationPrincipal UserDetailsImpl principal,@PathVariable UUID notificationId) {
        if(principal==null) throw new SoundConnectException(ErrorType.UNAUTHORIZED);
        var target=CollabNotificationIdentity.resolve(jdbc,principal.getId(),notificationId,false)
            .orElseThrow(()->new SoundConnectException(ErrorType.NOTIFICATION_NOT_FOUND));
        return BaseResponse.<NotificationResponseDto>builder().success(true).code(200).data(target.notification()).build();
    }
}
