package com.berkayb.soundconnect.auth.security;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Checks the authenticated session through the regular JWT filter without loading a product resource. */
@RestController
public class SessionAccessController {
    static final String PATH = "/api/v1/session/access";

    @GetMapping(PATH)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> access() {
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
