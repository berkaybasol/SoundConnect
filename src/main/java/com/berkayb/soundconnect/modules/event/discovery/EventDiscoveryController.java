package com.berkayb.soundconnect.modules.event.discovery;

import com.berkayb.soundconnect.shared.response.BaseResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class EventDiscoveryController {
    private final EventDiscoveryService service;

    @GetMapping("/api/v1/events/discovery")
    public BaseResponse<EventDiscoveryPage> discover(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam UUID cityId,
            @RequestParam(required = false) UUID districtId,
            @RequestParam(required = false) UUID neighborhoodId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return BaseResponse.<EventDiscoveryPage>builder().success(true).code(200)
                .message("Etkinlikler listelendi.")
                .data(service.discover(date, cityId, districtId, neighborhoodId, page, size)).build();
    }
}
