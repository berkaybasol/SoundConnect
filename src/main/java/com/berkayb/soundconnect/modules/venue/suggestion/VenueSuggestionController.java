package com.berkayb.soundconnect.modules.venue.suggestion;

import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
public class VenueSuggestionController {
    private final VenueSuggestionService service;
    @PostMapping(value = "/api/v1/venue-suggestions", consumes = "application/json")
    public ResponseEntity<BaseResponse<Accepted>> suggest(@Valid @RequestBody VenueSuggestionRequest request) {
        service.accept(request);
        return ResponseEntity.accepted().header("Cache-Control", "no-store")
                .body(BaseResponse.<Accepted>builder().success(true).code(202)
                        .message("Mekan önerin alındı.").data(new Accepted(true)).build());
    }
    public record Accepted(boolean accepted) { }
}
