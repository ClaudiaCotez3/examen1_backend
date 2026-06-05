package com.example.backend.service;

import com.example.backend.dto.ConsultationCaseDTO;
import com.example.backend.dto.MobileLoginResponseDTO;
import com.example.backend.dto.MobilePolicyDTO;
import com.example.backend.dto.MobileStartCaseRequestDTO;
import com.example.backend.dto.StartCaseRequestDTO;
import com.example.backend.dto.StartCaseResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.mapper.BusinessPolicyMapper;
import com.example.backend.mapper.FormMapper;
import com.example.backend.model.BusinessPolicy;
import com.example.backend.model.Customer;
import com.example.backend.model.Procedure;
import com.example.backend.repository.BusinessPolicyRepository;
import com.example.backend.repository.CustomerRepository;
import com.example.backend.repository.ProcedureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Portal móvil del CLIENTE (app Flutter).
 *
 * Los clientes no son {@link com.example.backend.model.User}s del sistema
 * (no tienen cuenta ni rol): son los {@link Customer} de la Opción B,
 * creados automáticamente cuando abren su primer trámite. Por eso el
 * "login" es la verificación del par correo + CI capturado en el
 * formulario inicial, y cada request del portal re-presenta esas
 * credenciales (modelo credential-per-request — sin tokens que expiren
 * en el teléfono, y sin tocar el esquema de roles del backoffice).
 *
 * Capacidades:
 *   - login / mis trámites → reusa la proyección de Consultas
 *     (ConsultationCaseDTO: timeline de áreas + etapas actuales).
 *   - catálogo de políticas + formulario inicial (Módulo 3).
 *   - iniciar trámite desde la app: la identidad verificada SOBREESCRIBE
 *     los campos reservados cliente_* antes de delegar al motor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MobilePortalService {

    private final CustomerRepository customerRepository;
    private final ProcedureRepository procedureRepository;
    private final BusinessPolicyRepository businessPolicyRepository;
    private final ConsultationService consultationService;
    private final WorkflowEngineService workflowEngineService;
    private final FormMapper formMapper;

    // ── Autenticación (correo + CI) ───────────────────────────────────────

    /**
     * Valida el par correo + CI contra la colección de clientes. Lanza 400
     * con mensaje genérico cuando no coincide — nunca revela cuál de los
     * dos datos falló.
     */
    public Customer authenticate(String email, String ci) {
        String cleanEmail = clean(email);
        String cleanCi = clean(ci);
        if (cleanEmail == null || cleanCi == null) {
            throw new BadRequestException("Correo y CI son obligatorios");
        }
        Customer customer = customerRepository.findFirstByEmailIgnoreCase(cleanEmail)
                .orElseThrow(MobilePortalService::invalidCredentials);
        String storedCi = clean(customer.getCi());
        if (storedCi == null || !storedCi.equalsIgnoreCase(cleanCi)) {
            throw invalidCredentials();
        }
        return customer;
    }

    private static BadRequestException invalidCredentials() {
        return new BadRequestException(
                "No encontramos un cliente con ese correo y CI. "
                        + "Verifica los datos con los que abriste tu trámite.");
    }

    // ── Login + mis trámites ──────────────────────────────────────────────

    public MobileLoginResponseDTO login(String email, String ci) {
        Customer customer = authenticate(email, ci);
        return MobileLoginResponseDTO.builder()
                .customerId(customer.getId().toHexString())
                .name(customer.getNombre())
                .email(customer.getEmail())
                .ci(customer.getCi())
                .cases(casesOf(customer))
                .build();
    }

    public List<ConsultationCaseDTO> getCases(String email, String ci) {
        return casesOf(authenticate(email, ci));
    }

    /**
     * Trámites del cliente, resueltos por {@code cliente_id} (identidad de
     * la Opción B — exacta, no heurística) y proyectados con la misma vista
     * de progreso que usa Consultas.
     */
    private List<ConsultationCaseDTO> casesOf(Customer customer) {
        return procedureRepository.findAll().stream()
                .filter(p -> customer.getId().equals(p.getClienteId()))
                .sorted(Comparator.comparing(
                        Procedure::getFechaInicio,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(p -> consultationService.getCase(p.getId().toHexString()))
                .toList();
    }

    // ── Catálogo de políticas (Módulo 3) ──────────────────────────────────

    /** Políticas disponibles para iniciar trámite (excluye archivadas). */
    public List<MobilePolicyDTO> listPolicies() {
        return businessPolicyRepository.findAll().stream()
                .filter(p -> !"ARCHIVED".equals(p.getEstado()))
                .sorted(Comparator.comparing(
                        BusinessPolicy::getNombre,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(this::toPolicyDto)
                .toList();
    }

    public MobilePolicyDTO getPolicy(String policyId) {
        if (policyId == null || !ObjectId.isValid(policyId)) {
            throw new BadRequestException("Invalid policyId: " + policyId);
        }
        BusinessPolicy policy = businessPolicyRepository.findById(new ObjectId(policyId))
                .orElseThrow(() -> new BadRequestException("Política no encontrada: " + policyId));
        return toPolicyDto(policy);
    }

    private MobilePolicyDTO toPolicyDto(BusinessPolicy policy) {
        return MobilePolicyDTO.builder()
                .id(policy.getId() != null ? policy.getId().toHexString() : null)
                .name(policy.getNombre())
                .description(policy.getDescripcion())
                .startFormDefinition(formMapper.toDefinitionDTO(policy.getStartFormDefinition()))
                .build();
    }

    // ── Iniciar trámite desde la app (Módulo 3) ───────────────────────────

    public StartCaseResponseDTO startCase(MobileStartCaseRequestDTO request) {
        // Cliente EXISTENTE → su identidad verificada manda. Cliente NUEVO
        // (invitado que llegó por "Iniciar un nuevo trámite") → no hay
        // registro aún: el correo + CI que escribió en el formulario son
        // su identidad fundacional, el motor creará su Customer al iniciar
        // el caso y desde entonces podrá entrar a la app con esos datos.
        Customer customer = null;
        try {
            customer = authenticate(request.getEmail(), request.getCi());
        } catch (BadRequestException ignored) {
            // invitado — identidad nueva
        }

        Map<String, Object> data = new HashMap<>(
                request.getStartFormData() != null ? request.getStartFormData() : Map.of());

        if (customer != null) {
            if (customer.getNombre() != null && !customer.getNombre().isBlank()) {
                data.put(BusinessPolicyMapper.CUSTOMER_NAME_FIELD, customer.getNombre());
            } else {
                data.putIfAbsent(BusinessPolicyMapper.CUSTOMER_NAME_FIELD, customer.getEmail());
            }
            data.put(BusinessPolicyMapper.CUSTOMER_EMAIL_FIELD, customer.getEmail());
            data.put(BusinessPolicyMapper.CUSTOMER_CI_FIELD, customer.getCi());
            log.info("Mobile start-case: customer={} policy={}",
                    customer.getId(), request.getPolicyId());
        } else {
            String email = clean(request.getEmail());
            String ci = clean(request.getCi());
            if (email == null || ci == null) {
                throw new BadRequestException("Correo y CI son obligatorios para iniciar el trámite");
            }
            data.put(BusinessPolicyMapper.CUSTOMER_EMAIL_FIELD, email);
            data.put(BusinessPolicyMapper.CUSTOMER_CI_FIELD, ci);
            data.putIfAbsent(BusinessPolicyMapper.CUSTOMER_NAME_FIELD, email);
            log.info("Mobile start-case (nuevo cliente): email={} policy={}",
                    email, request.getPolicyId());
        }

        return workflowEngineService.startCase(StartCaseRequestDTO.builder()
                .policyId(request.getPolicyId())
                .startFormData(data)
                .build());
    }

    private String clean(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        return s.isEmpty() ? null : s;
    }
}
