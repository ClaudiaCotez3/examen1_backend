package com.example.backend.service;

import com.example.backend.mapper.BusinessPolicyMapper;
import com.example.backend.model.Customer;
import com.example.backend.repository.CustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Single place where customer identity is resolved (Opción B).
 *
 * Reads the RESERVED start-form fields ({@code cliente_email},
 * {@code cliente_ci}, {@code cliente_nombre} — auto-prepended to every
 * policy's start form by {@link BusinessPolicyMapper}) and finds-or-creates
 * the matching {@link Customer}:
 *
 *   1. email (case-insensitive) — primary key.
 *   2. CI — fallback when no email was captured.
 *   3. Otherwise a new Customer is created (needs at least one of
 *      email / ci / nombre; fully anonymous cases stay unlinked).
 *
 * The principle: identity resolution happens ONCE, at write time. Every
 * downstream consumer (Repositorio admin, NL reports, predictive
 * analytics) joins by {@code cliente_id} instead of re-running heuristics.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerResolutionService {

    private final CustomerRepository customerRepository;

    /**
     * Resolves (or creates) the customer behind a start-form payload.
     * Returns null when the payload carries no identifiable customer data
     * — the caller stores the trámite unlinked rather than failing.
     */
    public ObjectId resolveOrCreate(Map<String, Object> startFormData) {
        if (startFormData == null || startFormData.isEmpty()) return null;

        String email = clean(startFormData.get(BusinessPolicyMapper.CUSTOMER_EMAIL_FIELD));
        String ci = clean(startFormData.get(BusinessPolicyMapper.CUSTOMER_CI_FIELD));
        String nombre = clean(startFormData.get(BusinessPolicyMapper.CUSTOMER_NAME_FIELD));
        if (email == null && ci == null && nombre == null) return null;

        Optional<Customer> existing = Optional.empty();
        if (email != null) {
            existing = customerRepository.findFirstByEmailIgnoreCase(email);
        }
        if (existing.isEmpty() && ci != null) {
            // CI fallback SOLO cuando el cliente encontrado no tiene correo
            // (o tiene el mismo). Dos personas distintas pueden compartir CI
            // por errores de captura — si el correo difiere, son identidades
            // separadas y se crea un cliente nuevo. Sin esta guarda, el
            // trámite de "gabi@gmail.com" se absorbería en el cliente de
            // "cami@gmail.com" solo por coincidir el CI, y gabi nunca
            // podría entrar a la app móvil.
            existing = customerRepository.findFirstByCi(ci)
                    .filter(c -> isBlank(c.getEmail())
                            || (email != null && email.equalsIgnoreCase(c.getEmail())));
        }

        LocalDateTime now = LocalDateTime.now();
        if (existing.isPresent()) {
            // Enrich missing identity fields, never overwrite good data.
            Customer customer = existing.get();
            boolean dirty = false;
            if (isBlank(customer.getEmail()) && email != null) {
                customer.setEmail(email);
                dirty = true;
            }
            if (isBlank(customer.getCi()) && ci != null) {
                customer.setCi(ci);
                dirty = true;
            }
            if (isBlank(customer.getNombre()) && nombre != null) {
                customer.setNombre(nombre);
                dirty = true;
            }
            if (dirty) {
                customer.setFechaActualizacion(now);
                customerRepository.save(customer);
            }
            return customer.getId();
        }

        Customer created = customerRepository.save(Customer.builder()
                .nombre(nombre)
                .email(email)
                .ci(ci)
                .fechaCreacion(now)
                .fechaActualizacion(now)
                .build());
        log.info("Customer created: {} ({})", created.getId(),
                nombre != null ? nombre : (email != null ? email : ci));
        return created.getId();
    }

    private String clean(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
