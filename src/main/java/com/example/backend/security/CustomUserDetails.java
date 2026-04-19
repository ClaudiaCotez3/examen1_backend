package com.example.backend.security;

import com.example.backend.model.Role;
import com.example.backend.model.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security principal backed by the Mongo User document.
 * Exposes the user's id and role in a form the rest of the security layer
 * (JWT filter, controllers) can consume without coupling to Mongo types.
 */
@Getter
public class CustomUserDetails implements UserDetails {

    private final String id;
    private final String fullName;
    private final String email;
    private final String password;
    private final boolean enabled;
    private final String roleName;
    private final List<GrantedAuthority> authorities;

    public CustomUserDetails(User user, Role role) {
        this.id = user.getId() != null ? user.getId().toHexString() : null;
        this.fullName = user.getNombre();
        this.email = user.getEmail();
        this.password = user.getPassword();
        this.enabled = Boolean.TRUE.equals(user.getActivo());
        this.roleName = role != null ? role.getNombre() : null;
        this.authorities = role != null
                ? List.of(new SimpleGrantedAuthority("ROLE_" + role.getNombre()))
                : List.of();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getUsername() {
        return email;
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
        return enabled;
    }
}
