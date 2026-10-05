package com.berkayb.soundconnect.modules.notification.dlq;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.core.JsonParser;
import com.berkayb.soundconnect.shared.exception.SoundConnectException;
import com.berkayb.soundconnect.shared.exception.ErrorType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/notifications/dlq")
@PreAuthorize("!hasRole('LISTENER') and hasAnyRole('OWNER','ADMIN')")
public class NotificationDlqController {
    private final NotificationDlqOperations operations;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public NotificationDlqController(NotificationDlqOperations operations) { this.operations = operations; }
    @ModelAttribute void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping("/summary") public BaseResponse<NotificationDlqOperations.Summary> summary() { return response(operations.summary()); }
    @PostMapping("/inspect") public BaseResponse<NotificationDlqOperations.Result> inspect(@AuthenticationPrincipal UserDetailsImpl user,
                                                                                         HttpServletRequest request) throws Exception {
        if (request.getInputStream().read() != -1) throw new SoundConnectException(ErrorType.BAD_REQUEST);
        return response(operations.inspect(user.getId()));
    }
    @PostMapping("/replay") public BaseResponse<NotificationDlqOperations.Result> replay(@AuthenticationPrincipal UserDetailsImpl user,
                                                                                       HttpServletRequest request) throws Exception {
        byte[] body = request.getInputStream().readNBytes(2049);
        NotificationDlqOperations.Selection selection;
        try {
            if (body.length > 2048) throw new IllegalArgumentException();
            var node = json.readTree(body);
            if (node == null || !node.isObject() || node.size() != 2 || !node.has("eventId") || !node.has("fingerprint")) throw new IllegalArgumentException();
            String id = node.get("eventId").asText();
            String fingerprint = node.get("fingerprint").asText();
            UUID eventId = UUID.fromString(id);
            if (!eventId.toString().equals(id) || !fingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException();
            selection = new NotificationDlqOperations.Selection(eventId, fingerprint);
        } catch (Exception e) { throw new SoundConnectException(ErrorType.BAD_REQUEST); }
        return response(operations.replay(user.getId(), selection));
    }
    private static <T> BaseResponse<T> response(T value) {
        return BaseResponse.<T>builder().success(true).code(200).message("Notification DLQ operations").data(value).build();
    }
}
