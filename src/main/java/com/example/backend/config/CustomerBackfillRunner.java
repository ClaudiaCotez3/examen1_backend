package com.example.backend.config;

import com.example.backend.mapper.BusinessPolicyMapper;
import com.example.backend.model.Customer;
import com.example.backend.model.Procedure;
import com.example.backend.repository.CustomerRepository;
import com.example.backend.repository.ProcedureRepository;
import com.example.backend.service.CustomerResolutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Idempotent startup migration for Opción B. Two passes over `tramites`:
 *
 *   1. BACKFILL — procedures with {@code clienteId == null} get linked to
 *      their {@link Customer} (find-or-create from the reserved
 *      cliente_* start-form fields).
 *
 *   2. REPAIR — procedures whose linked customer's email does NOT match
 *      the email captured on their own start form get re-resolved. This
 *      heals links made before the CI-fallback guard existed (e.g. a
 *      trámite of gabi@gmail.com absorbed into cami@gmail.com's customer
 *      because both shared the same CI), so every cliente can log into
 *      the mobile portal with the email they actually used.
 *
 * Both passes are no-ops once the data is consistent, and neither can
 * block application startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerBackfillRunner implements ApplicationRunner {

    private final ProcedureRepository procedureRepository;
    private final CustomerRepository customerRepository;
    private final CustomerResolutionService customerResolutionService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int linked = 0;
            int repaired = 0;
            int unidentified = 0;

            for (Procedure procedure : procedureRepository.findAll()) {
                if (procedure.getClienteId() == null) {
                    // Pass 1 — backfill
                    ObjectId clienteId = customerResolutionService
                            .resolveOrCreate(procedure.getStartFormData());
                    if (clienteId != null) {
                        procedure.setClienteId(clienteId);
                        procedureRepository.save(procedure);
                        linked++;
                    } else {
                        unidentified++;
                    }
                    continue;
                }

                // Pass 2 — repair mismatched links (email is the primary key)
                String formEmail = emailOf(procedure);
                if (formEmail == null) continue;
                Optional<Customer> current =
                        customerRepository.findById(procedure.getClienteId());
                String linkedEmail = current.map(Customer::getEmail).orElse(null);
                boolean mismatch = current.isEmpty()
                        || (linkedEmail != null && !linkedEmail.isBlank()
                            && !linkedEmail.equalsIgnoreCase(formEmail));
                if (!mismatch) continue;

                ObjectId resolved = customerResolutionService
                        .resolveOrCreate(procedure.getStartFormData());
                if (resolved != null && !resolved.equals(procedure.getClienteId())) {
                    log.info("Customer repair: case {} relinked {} -> {} ({})",
                            procedure.getCodigo(), procedure.getClienteId(),
                            resolved, formEmail);
                    procedure.setClienteId(resolved);
                    procedureRepository.save(procedure);
                    repaired++;
                }
            }

            if (linked > 0 || repaired > 0 || unidentified > 0) {
                log.info("Customer backfill: {} vinculados, {} reparados, {} sin identificar",
                        linked, repaired, unidentified);
            }
        } catch (Exception e) {
            // The backfill must never block application startup.
            log.warn("Customer backfill failed (will retry next start)", e);
        }
    }

    private String emailOf(Procedure procedure) {
        Map<String, Object> data = procedure.getStartFormData();
        if (data == null) return null;
        Object raw = data.get(BusinessPolicyMapper.CUSTOMER_EMAIL_FIELD);
        if (raw == null) return null;
        String email = String.valueOf(raw).trim();
        return email.isEmpty() ? null : email;
    }
}
