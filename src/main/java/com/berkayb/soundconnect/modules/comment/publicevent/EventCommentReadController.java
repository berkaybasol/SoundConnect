package com.berkayb.soundconnect.modules.comment.publicevent;

import com.berkayb.soundconnect.auth.security.UserDetailsImpl;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentReplyResponseDto;
import com.berkayb.soundconnect.modules.comment.dto.response.CommentResponseDto;
import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events/{eventId}/comments")
@RequiredArgsConstructor
public class EventCommentReadController {
    private final EventCommentReadService service;

    @GetMapping
    public BaseResponse<Page<CommentResponseDto>> getComments(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID eventId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return BaseResponse.<Page<CommentResponseDto>>builder().success(true).code(200)
                .message("Yorumlar listelendi.")
                .data(service.getComments(principal == null ? null : principal.getId(), eventId, page, size)).build();
    }

    @GetMapping("/{commentId}/replies")
    public BaseResponse<Page<CommentReplyResponseDto>> getReplies(
            @AuthenticationPrincipal UserDetailsImpl principal,
            @PathVariable UUID eventId,
            @PathVariable UUID commentId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return BaseResponse.<Page<CommentReplyResponseDto>>builder().success(true).code(200)
                .message("Yanıtlar listelendi.")
                .data(service.getReplies(principal == null ? null : principal.getId(), eventId, commentId, page, size)).build();
    }
}
