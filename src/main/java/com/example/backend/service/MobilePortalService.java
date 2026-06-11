package com.example.backend.service;

import com.example.backend.dto.ConsultationCaseDTO;
import com.example.backend.dto.FormDefinitionDTO;
import com.example.backend.dto.FormFieldDTO;
import com.example.backend.dto.MobileLoginResponseDTO;
import com.example.backend.dto.MobileNotificationDTO;
import com.example.backend.dto.MobilePolicyDTO;
import com.example.backend.dto.MobileStartCaseRequestDTO;
import com.example.backend.dto.StartCaseRequestDTO;
import com.example.backend.dto.StartCaseResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.mapper.BusinessPolicyMapper;
import com.example.backend.mapper.FormMapper;
import com.example.backend.model.BusinessPolicy;
import com.example.backend.model.Customer;
import com.example.backend.model.CustomerDevice;
import com.example.backend.model.CustomerNotification;
import com.example.backend.model.Procedure;
import com.example.backend.repository.BusinessPolicyRepository;
import com.example.backend.repository.CustomerDeviceRepository;
import com.example.backend.repository.CustomerNotificationRepository;
import com.example.backend.repository.CustomerRepository;
import com.example.backend.repository.ProcedureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    private final CustomerDeviceRepository customerDeviceRepository;
    private final CustomerNotificationRepository customerNotificationRepository;
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

    /**
     * Políticas disponibles para iniciar trámite (excluye archivadas).
     *
     * Si llegan credenciales válidas (cliente ya registrado), se ocultan
     * los campos reservados cliente_* del formulario inicial: la app no
     * debe volver a pedir datos que ya conocemos. El motor los sobreescribe
     * con la identidad verificada al iniciar el caso de todos modos.
     */
    public List<MobilePolicyDTO> listPolicies(String email, String ci) {
        boolean stripCustomerFields = isReturningCustomer(email, ci);
        return businessPolicyRepository.findAll().stream()
                .filter(p -> !"ARCHIVED".equals(p.getEstado()))
                .sorted(Comparator.comparing(
                        BusinessPolicy::getNombre,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(p -> toPolicyDto(p, stripCustomerFields))
                .toList();
    }

    public MobilePolicyDTO getPolicy(String policyId, String email, String ci) {
        if (policyId == null || !ObjectId.isValid(policyId)) {
            throw new BadRequestException("Invalid policyId: " + policyId);
        }
        BusinessPolicy policy = businessPolicyRepository.findById(new ObjectId(policyId))
                .orElseThrow(() -> new BadRequestException("Política no encontrada: " + policyId));
        return toPolicyDto(policy, isReturningCustomer(email, ci));
    }

    /**
     * ¿El par correo + CI corresponde a un cliente ya registrado? Best-effort
     * y silencioso: un invitado nuevo (o credenciales ausentes/erróneas)
     * simplemente recibe el formulario completo con los campos cliente_*.
     */
    private boolean isReturningCustomer(String email, String ci) {
        if (clean(email) == null || clean(ci) == null) {
            return false;
        }
        try {
            authenticate(email, ci);
            return true;
        } catch (BadRequestException ignored) {
            return false;
        }
    }

    private MobilePolicyDTO toPolicyDto(BusinessPolicy policy, boolean stripCustomerFields) {
        FormDefinitionDTO form = formMapper.toDefinitionDTO(policy.getStartFormDefinition());
        if (stripCustomerFields && form != null && form.getFields() != null) {
            List<FormFieldDTO> visible = form.getFields().stream()
                    .filter(f -> f == null || !isReservedCustomerField(f.getName()))
                    .toList();
            form = FormDefinitionDTO.builder().fields(visible).build();
        }
        return MobilePolicyDTO.builder()
                .id(policy.getId() != null ? policy.getId().toHexString() : null)
                .name(policy.getNombre())
                .description(policy.getDescripcion())
                .startFormDefinition(form)
                .build();
    }

    private static boolean isReservedCustomerField(String name) {
        return BusinessPolicyMapper.CUSTOMER_NAME_FIELD.equals(name)
                || BusinessPolicyMapper.CUSTOMER_EMAIL_FIELD.equals(name)
                || BusinessPolicyMapper.CUSTOMER_CI_FIELD.equals(name);
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

    // ── Notificaciones push (FCM) ─────────────────────────────────────────

    /**
     * Registra (upsert por token) el dispositivo del cliente para push.
     * Si otro cliente inicia sesión en el mismo teléfono, el token se
     * re-asigna — el dueño anterior deja de recibir avisos en ese equipo.
     */
    public void registerDevice(String email, String ci, String fcmToken, String platform) {
        Customer customer = authenticate(email, ci);
        String token = clean(fcmToken);
        if (token == null) {
            throw new BadRequestException("fcmToken es obligatorio");
        }
        CustomerDevice device = customerDeviceRepository.findFirstByFcmToken(token)
                .orElseGet(() -> CustomerDevice.builder().fcmToken(token).build());
        device.setClienteId(customer.getId());
        device.setPlataforma(clean(platform));
        device.setFechaActualizacion(LocalDateTime.now());
        customerDeviceRepository.save(device);
        log.info("Dispositivo registrado para push: cliente={} plataforma={}",
                customer.getId(), platform);
    }

    /** Últimas notificaciones del cliente (campanita de la app). */
    public List<MobileNotificationDTO> getNotifications(String email, String ci) {
        Customer customer = authenticate(email, ci);
        return customerNotificationRepository
                .findTop50ByClienteIdOrderByFechaDesc(customer.getId()).stream()
                .map(n -> MobileNotificationDTO.builder()
                        .id(n.getId().toHexString())
                        .caseId(n.getTramiteId() != null
                                ? n.getTramiteId().toHexString() : null)
                        .caseCode(n.getCaseCode())
                        .type(n.getTipo())
                        .title(n.getTitulo())
                        .message(n.getMensaje())
                        .createdAt(n.getFecha())
                        .read(n.isLeida())
                        .build())
                .toList();
    }

    /** Marca como leídas todas las notificaciones del cliente. */
    public void markNotificationsRead(String email, String ci) {
        Customer customer = authenticate(email, ci);
        List<CustomerNotification> unread =
                customerNotificationRepository.findByClienteIdAndLeidaFalse(customer.getId());
        for (CustomerNotification notification : unread) {
            notification.setLeida(true);
        }
        if (!unread.isEmpty()) {
            customerNotificationRepository.saveAll(unread);
        }
    }

    private String clean(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        return s.isEmpty() ? null : s;
    }
}
