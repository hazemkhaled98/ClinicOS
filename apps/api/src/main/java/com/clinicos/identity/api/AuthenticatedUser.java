package com.clinicos.identity.api;

import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Represents an authenticated user in a Spring Security context. Carries the
 * user's id and username; role/permission resolution happens on clinic
 * selection (later slice).
 */
public class AuthenticatedUser implements UserDetails {

    private final UUID id;
    private final UUID clinicId;
    private final String username;
    private final String passwordHash;

    public AuthenticatedUser(UUID id, UUID clinicId, String username, String passwordHash) {
        this.id = id;
        this.clinicId = clinicId;
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public UUID getId() {
        return id;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public java.util.Collection<? extends GrantedAuthority> getAuthorities() {
        return java.util.List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
