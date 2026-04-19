package com.example.backend.service;

import com.example.backend.dto.AuthUserDTO;
import com.example.backend.dto.LoginRequestDTO;
import com.example.backend.dto.LoginResponseDTO;
import com.example.backend.dto.RegisterRequestDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.model.Role;
import com.example.backend.model.User;
import com.example.backend.repository.RoleRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.CustomUserDetails;
import com.example.backend.security.CustomUserDetailsService;
import com.example.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * Authentication use-cases: login, self-registration (admin-managed), and "me".
 * Password hashing and JWT issuance both happen here so controllers stay thin.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final CustomUserDetailsService userDetailsService;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public LoginResponseDTO login(LoginRequestDTO request) {
        Authentication auth;
        try {
            auth = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getEmail(), request.getPassword()));
        } catch (DisabledException ex) {
            throw new BadRequestException("User account is disabled");
        } catch (AuthenticationException ex) {
            throw new InvalidCredentialsException();
        }

        CustomUserDetails principal = (CustomUserDetails) auth.getPrincipal();
        String token = jwtService.generateToken(principal);

        return LoginResponseDTO.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresInMs(jwtService.getExpirationMs())
                .user(toAuthUser(principal))
                .build();
    }

    public AuthUserDTO register(RegisterRequestDTO request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email is already registered: " + request.getEmail());
        }

        Role role = roleRepository.findByNombre(request.getRole())
                .orElseThrow(() -> new ResourceNotFoundException("Role '" + request.getRole() + "' not found"));

        User user = User.builder()
                .nombre(request.getFullName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .rolId(role.getId())
                .activo(true)
                .fechaCreacion(LocalDateTime.now())
                .fechaActualizacion(LocalDateTime.now())
                .build();

        User saved = userRepository.save(user);
        return AuthUserDTO.builder()
                .id(saved.getId().toHexString())
                .fullName(saved.getNombre())
                .email(saved.getEmail())
                .roles(List.of(role.getNombre()))
                .build();
    }

    public AuthUserDTO me(String email) {
        CustomUserDetails principal = (CustomUserDetails) userDetailsService.loadUserByUsername(email);
        return toAuthUser(principal);
    }

    private AuthUserDTO toAuthUser(CustomUserDetails principal) {
        List<String> roles = principal.getRoleName() != null
                ? List.of(principal.getRoleName())
                : Collections.emptyList();

        return AuthUserDTO.builder()
                .id(principal.getId())
                .fullName(principal.getFullName())
                .email(principal.getEmail())
                .roles(roles)
                .build();
    }

    /** Marker exception so the global handler maps bad login to 401. */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("Invalid email or password");
        }
    }
}
