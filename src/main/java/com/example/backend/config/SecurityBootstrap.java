package com.example.backend.config;

import com.example.backend.model.Role;
import com.example.backend.model.User;
import com.example.backend.repository.RoleRepository;
import com.example.backend.repository.UserRepository;
import com.example.backend.security.RoleName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Idempotent bootstrap that guarantees the four canonical roles exist and, on
 * first run only, creates a default administrator. Running on every boot is
 * safe: existing roles/users are never modified.
 *
 * CommandLineRunner is chosen over manual seeding because (a) it runs after the
 * application context is ready (repositories available), and (b) it keeps the
 * seed under source control so every environment starts identically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityBootstrap implements CommandLineRunner {

    private static final Map<String, String> ROLE_DESCRIPTIONS = Map.of(
            RoleName.ADMIN, "Full system administrator",
            RoleName.OPERATOR, "Operational user handling workflow tasks",
            RoleName.CONSULTATION, "Read-only consultation of cases and processes",
            RoleName.SUPERVISOR, "Supervises operators and monitors processes");

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${security.bootstrap.admin.email}")
    private String adminEmail;

    @Value("${security.bootstrap.admin.password}")
    private String adminPassword;

    @Value("${security.bootstrap.admin.name}")
    private String adminName;

    @Override
    public void run(String... args) {
        seedRoles();
        seedAdminUser();
    }

    private void seedRoles() {
        for (String name : RoleName.ALL) {
            roleRepository.findByNombre(name).orElseGet(() -> {
                Role role = Role.builder()
                        .nombre(name)
                        .descripcion(ROLE_DESCRIPTIONS.get(name))
                        .build();
                Role saved = roleRepository.save(role);
                log.info("Seeded role '{}'", name);
                return saved;
            });
        }
    }

    private void seedAdminUser() {
        if (userRepository.existsByEmail(adminEmail)) {
            return;
        }
        Role adminRole = roleRepository.findByNombre(RoleName.ADMIN)
                .orElseThrow(() -> new IllegalStateException("ADMIN role missing after seeding"));

        User admin = User.builder()
                .nombre(adminName)
                .email(adminEmail)
                .password(passwordEncoder.encode(adminPassword))
                .rolId(adminRole.getId())
                .activo(true)
                .fechaCreacion(LocalDateTime.now())
                .fechaActualizacion(LocalDateTime.now())
                .build();
        userRepository.save(admin);
        log.warn("Bootstrap admin created with email='{}'. Change the password immediately.", adminEmail);
    }
}
