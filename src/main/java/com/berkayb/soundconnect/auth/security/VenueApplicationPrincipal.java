package com.berkayb.soundconnect.auth.security;

import com.berkayb.soundconnect.modules.user.entity.User;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** A restricted credential never inherits roles when its applicant is approved. */
public final class VenueApplicationPrincipal extends UserDetailsImpl {
    private final UUID applicationId;

    public VenueApplicationPrincipal(User user, UUID applicationId) {
        super(user);
        this.applicationId = applicationId;
    }

    public UUID getApplicationId() { return applicationId; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() { return List.of(); }
}
