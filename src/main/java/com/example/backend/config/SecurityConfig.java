package com.example.backend.config;

import com.example.backend.security.CustomAccessDeniedHandler;
import com.example.backend.security.CustomUserDetailsService;
import com.example.backend.security.JwtAuthenticationEntryPoint;
import com.example.backend.security.JwtAuthenticationFilter;
import com.example.backend.security.RoleName;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Stateless JWT-based security configuration.
 *
 * Route rules (role-based):
 *   /api/auth/**         — public (login/register flow lives here)
 *   /api/admin/**        — ADMIN
 *   /api/supervisor/**   — SUPERVISOR, ADMIN
 *   /api/operator/**     — OPERATOR, SUPERVISOR, ADMIN
 *   /api/consultation/** — CONSULTATION, SUPERVISOR, ADMIN
 *
 * Workflow domain endpoints:
 *   /api/users, /api/roles                  — ADMIN
 *   /api/business-policies, /api/activities,
 *   /api/flows, /api/lanes                  — ADMIN (design-time)
 *   /api/cases                              — OPERATOR, SUPERVISOR, ADMIN, CONSULTATION (read/list)
 *   /api/activity-instances                 — OPERATOR, SUPERVISOR, ADMIN
 *
 * Method-level @PreAuthorize is enabled for finer-grained rules.
 */
@Configuration
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final CustomUserDetailsService userDetailsService;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // Public (only login is fully open; /me and /register require auth + role)
                        .requestMatchers("/api/auth/login").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Portal móvil del CLIENTE (app Flutter). Los clientes
                        // no son Users del sistema: cada endpoint exige y
                        // valida el par correo + CI contra la colección
                        // `clientes` (MobilePortalService.authenticate), así
                        // que permitAll aquí NO significa acceso anónimo a
                        // datos — sin credenciales correctas todo responde 400.
                        .requestMatchers("/api/mobile/**").permitAll()
                        // OnlyOffice Document Server (server-to-server). El DS
                        // no porta el JWT de usuarios: cada request trae un
                        // token firmado de propósito único (secreto propio de
                        // OnlyOffice) validado en OnlyOfficeController — sin
                        // token válido todo responde 401/400.
                        .requestMatchers("/api/onlyoffice/download",
                                "/api/onlyoffice/callback").permitAll()
                        // STOMP handshake endpoint. Auth happens at the
                        // STOMP CONNECT frame via StompJwtChannelInterceptor,
                        // not at the HTTP upgrade.
                        .requestMatchers("/ws/policies/**").permitAll()

                        // Role-segmented namespaces
                        .requestMatchers("/api/admin/**").hasRole(RoleName.ADMIN)
                        .requestMatchers("/api/supervisor/**").hasAnyRole(RoleName.SUPERVISOR, RoleName.ADMIN)
                        .requestMatchers("/api/operator/**").hasAnyRole(RoleName.OPERATOR, RoleName.SUPERVISOR, RoleName.ADMIN)
                        .requestMatchers("/api/consultation/**").hasAnyRole(RoleName.CONSULTATION, RoleName.SUPERVISOR, RoleName.ADMIN)

                        // Admin-only domain resources (user and role management, workflow design)
                        .requestMatchers("/api/users/**", "/api/roles/**").hasRole(RoleName.ADMIN)
                        // Read access to policies is open to any authenticated role so the
                        // "Iniciar trámite" flow (customer-facing consultor) can list
                        // active policies and their versions. Write operations still require ADMIN.
                        .requestMatchers(HttpMethod.GET, "/api/policies/**")
                                .hasAnyRole(RoleName.ADMIN, RoleName.SUPERVISOR,
                                        RoleName.CONSULTATION, RoleName.OPERATOR)
                        .requestMatchers("/api/policies/**",
                                "/api/activities/**",
                                "/api/flows/**",
                                "/api/lanes/**").hasRole(RoleName.ADMIN)

                        // Runtime / operational resources
                        .requestMatchers("/api/activity-instances/**")
                                .hasAnyRole(RoleName.OPERATOR, RoleName.SUPERVISOR, RoleName.ADMIN)
                        .requestMatchers("/api/case-files/**")
                                .hasAnyRole(RoleName.OPERATOR, RoleName.SUPERVISOR, RoleName.ADMIN, RoleName.CONSULTATION)
                        // Consultor-facing "Iniciar trámite" endpoint — same roles as
                        // the legacy case-files namespace so the front desk can launch
                        // a process and the operator Kanban can still read it.
                        .requestMatchers("/api/cases/**")
                                .hasAnyRole(RoleName.CONSULTATION, RoleName.SUPERVISOR, RoleName.ADMIN)
                        .requestMatchers("/api/forms/**")
                                .hasAnyRole(RoleName.OPERATOR, RoleName.SUPERVISOR, RoleName.ADMIN, RoleName.CONSULTATION)

                        .anyRequest().authenticated())
                .authenticationProvider(buildAuthenticationProvider())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Built inline (not exposed as a bean) so Spring Security does not warn
     * about having both a DaoAuthenticationProvider bean and a UserDetailsService
     * bean in the context.
     */
    private DaoAuthenticationProvider buildAuthenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOriginPatterns(List.of("*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization"));
        cfg.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
