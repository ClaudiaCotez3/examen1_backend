package com.example.backend.service;

import com.example.backend.dto.ClientCaseDTO;
import com.example.backend.dto.ClientCasesDTO;
import com.example.backend.dto.ClientSummaryDTO;
import com.example.backend.dto.RepositoryOverviewDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.model.BusinessPolicy;
import com.example.backend.model.CaseDocument;
import com.example.backend.model.Customer;
import com.example.backend.model.PolicyVersion;
import com.example.backend.model.Procedure;
import com.example.backend.repository.BusinessPolicyRepository;
import com.example.backend.repository.CaseDocumentRepository;
import com.example.backend.repository.CustomerRepository;
import com.example.backend.repository.PolicyVersionRepository;
import com.example.backend.repository.ProcedureRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Repositorio Documental (Opción B) — admin-facing read model.
 *
 * Master-detail over the customer dimension:
 *   - overview: KPIs + client list with case/document counters.
 *   - client file: every trámite of one client (entry point to the
 *     Expediente screen, which already exists).
 *
 * All joins go through {@code Procedure.clienteId} — identity was resolved
 * at write time, so this service only aggregates. Legacy trámites that
 * could not be linked are surfaced under the pseudo-client
 * {@link #UNIDENTIFIED_ID} ("Sin identificar") — the admin must never lose
 * sight of data because identity resolution failed.
 */
@Service
@RequiredArgsConstructor
public class AdminRepositoryService {

    public static final String UNIDENTIFIED_ID = "unidentified";

    private final CustomerRepository customerRepository;
    private final ProcedureRepository procedureRepository;
    private final CaseDocumentRepository caseDocumentRepository;
    private final PolicyVersionRepository policyVersionRepository;
    private final BusinessPolicyRepository businessPolicyRepository;

    // ── Nivel 1: lista de clientes ────────────────────────────────────────

    public RepositoryOverviewDTO getOverview(String search) {
        List<Customer> customers = customerRepository.findAllByOrderByNombreAsc();
        List<Procedure> procedures = procedureRepository.findAll();
        Map<ObjectId, Long> documentsByCase = countDocumentsByCase();

        // Group trámites by client (null clienteId → unidentified bucket).
        Map<ObjectId, List<Procedure>> casesByClient = procedures.stream()
                .filter(p -> p.getClienteId() != null)
                .collect(Collectors.groupingBy(Procedure::getClienteId));
        List<Procedure> unidentified = procedures.stream()
                .filter(p -> p.getClienteId() == null)
                .toList();

        List<ClientSummaryDTO> rows = new ArrayList<>();
        for (Customer customer : customers) {
            List<Procedure> cases = casesByClient.getOrDefault(customer.getId(), List.of());
            rows.add(toSummary(customer, cases, documentsByCase));
        }
        if (!unidentified.isEmpty()) {
            rows.add(ClientSummaryDTO.builder()
                    .id(UNIDENTIFIED_ID)
                    .name("Sin identificar")
                    .email(null)
                    .ci(null)
                    .caseCount(unidentified.size())
                    .documentCount(sumDocuments(unidentified, documentsByCase))
                    .lastCaseAt(lastCaseAt(unidentified))
                    .build());
        }

        List<ClientSummaryDTO> filtered = applySearch(rows, search);
        // Most recently active clients first; clients without cases sink.
        filtered.sort(Comparator.comparing(
                ClientSummaryDTO::getLastCaseAt,
                Comparator.nullsLast(Comparator.reverseOrder())));

        long totalDocuments = documentsByCase.values().stream().mapToLong(Long::longValue).sum();
        return RepositoryOverviewDTO.builder()
                .totalClients(customers.size() + (unidentified.isEmpty() ? 0 : 1))
                .totalCases(procedures.size())
                .totalDocuments(totalDocuments)
                .clients(filtered)
                .build();
    }

    // ── Nivel 2: trámites de un cliente ───────────────────────────────────

    public ClientCasesDTO getClientCases(String clientId) {
        Map<ObjectId, Long> documentsByCase = countDocumentsByCase();

        List<Procedure> cases;
        ClientSummaryDTO clientRow;
        if (UNIDENTIFIED_ID.equalsIgnoreCase(clientId)) {
            cases = procedureRepository.findAll().stream()
                    .filter(p -> p.getClienteId() == null)
                    .toList();
            clientRow = ClientSummaryDTO.builder()
                    .id(UNIDENTIFIED_ID)
                    .name("Sin identificar")
                    .caseCount(cases.size())
                    .documentCount(sumDocuments(cases, documentsByCase))
                    .lastCaseAt(lastCaseAt(cases))
                    .build();
        } else {
            if (!ObjectId.isValid(clientId)) {
                throw new BadRequestException("Invalid clientId: " + clientId);
            }
            Customer customer = customerRepository.findById(new ObjectId(clientId))
                    .orElseThrow(() -> new ResourceNotFoundException("Customer", clientId));
            cases = procedureRepository.findAll().stream()
                    .filter(p -> customer.getId().equals(p.getClienteId()))
                    .toList();
            clientRow = toSummary(customer, cases, documentsByCase);
        }

        Map<ObjectId, String> policyNamesByVersion = resolvePolicyNames(cases);
        List<ClientCaseDTO> caseRows = cases.stream()
                .sorted(Comparator.comparing(
                        Procedure::getFechaInicio,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(p -> ClientCaseDTO.builder()
                        .id(p.getId().toHexString())
                        .code(p.getCodigo())
                        .status(p.getEstado())
                        .policyName(policyNamesByVersion.get(p.getVersionPoliticaId()))
                        .documentCount(documentsByCase.getOrDefault(p.getId(), 0L))
                        .createdAt(p.getFechaInicio())
                        .finishedAt(p.getFechaFin())
                        .build())
                .toList();

        return ClientCasesDTO.builder()
                .client(clientRow)
                .cases(caseRows)
                .build();
    }

    // ── Internals ─────────────────────────────────────────────────────────

    private ClientSummaryDTO toSummary(
            Customer customer,
            List<Procedure> cases,
            Map<ObjectId, Long> documentsByCase) {
        return ClientSummaryDTO.builder()
                .id(customer.getId().toHexString())
                .name(customer.getNombre())
                .email(customer.getEmail())
                .ci(customer.getCi())
                .caseCount(cases.size())
                .documentCount(sumDocuments(cases, documentsByCase))
                .lastCaseAt(lastCaseAt(cases))
                .build();
    }

    /** documentCount per trámite in ONE pass (no N+1 count queries). */
    private Map<ObjectId, Long> countDocumentsByCase() {
        return caseDocumentRepository.findAll().stream()
                .filter(d -> d.getTramiteId() != null)
                .collect(Collectors.groupingBy(CaseDocument::getTramiteId, Collectors.counting()));
    }

    private long sumDocuments(List<Procedure> cases, Map<ObjectId, Long> documentsByCase) {
        return cases.stream()
                .mapToLong(p -> documentsByCase.getOrDefault(p.getId(), 0L))
                .sum();
    }

    private LocalDateTime lastCaseAt(List<Procedure> cases) {
        return cases.stream()
                .map(Procedure::getFechaInicio)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /** versionPoliticaId → policy display name, batch-resolved. */
    private Map<ObjectId, String> resolvePolicyNames(List<Procedure> cases) {
        List<ObjectId> versionIds = cases.stream()
                .map(Procedure::getVersionPoliticaId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (versionIds.isEmpty()) return Map.of();

        Map<ObjectId, PolicyVersion> versions = policyVersionRepository
                .findAllById(versionIds).stream()
                .collect(Collectors.toMap(PolicyVersion::getId, Function.identity()));
        List<ObjectId> policyIds = versions.values().stream()
                .map(PolicyVersion::getPoliticaId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<ObjectId, BusinessPolicy> policies = businessPolicyRepository
                .findAllById(policyIds).stream()
                .collect(Collectors.toMap(BusinessPolicy::getId, Function.identity()));

        return versions.entrySet().stream()
                .filter(e -> e.getValue().getPoliticaId() != null
                        && policies.containsKey(e.getValue().getPoliticaId()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> policies.get(e.getValue().getPoliticaId()).getNombre()));
    }

    /** Accent/case-insensitive filter over name, email and CI. */
    private List<ClientSummaryDTO> applySearch(List<ClientSummaryDTO> rows, String search) {
        if (search == null || search.isBlank()) return new ArrayList<>(rows);
        String needle = normalize(search);
        return rows.stream()
                .filter(c -> normalize(c.getName()).contains(needle)
                        || normalize(c.getEmail()).contains(needle)
                        || normalize(c.getCi()).contains(needle))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String normalize(String value) {
        if (value == null) return "";
        String lowered = value.trim().toLowerCase();
        return Normalizer.normalize(lowered, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}
