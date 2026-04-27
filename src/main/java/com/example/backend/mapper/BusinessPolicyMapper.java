package com.example.backend.mapper;

import com.example.backend.dto.BusinessPolicyRequestDTO;
import com.example.backend.dto.BusinessPolicyResponseDTO;
import com.example.backend.model.BusinessPolicy;
import com.example.backend.model.FormDefinition;
import com.example.backend.model.FormFieldDefinition;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class BusinessPolicyMapper {

    /**
     * Reserved start-form field names. Every policy must capture these so
     * the consultor's "Consultas" search can find a customer's trámite by
     * email / name / CI. They're auto-prepended to the form (idempotent)
     * so the admin doesn't have to remember to add them on every policy.
     */
    public static final String CUSTOMER_NAME_FIELD = "cliente_nombre";
    public static final String CUSTOMER_EMAIL_FIELD = "cliente_email";
    public static final String CUSTOMER_CI_FIELD = "cliente_ci";

    private final FormMapper formMapper;

    public BusinessPolicy toEntity(BusinessPolicyRequestDTO dto) {
        return BusinessPolicy.builder()
                .nombre(dto.getName())
                .descripcion(dto.getDescription())
                .bpmnXml(dto.getBpmnXml())
                .startFormDefinition(
                        ensureCustomerFields(formMapper.toEntity(dto.getStartFormDefinition())))
                .startFormSchema(copyMapOrNull(dto.getStartFormSchema()))
                .build();
    }

    public BusinessPolicyResponseDTO toResponse(BusinessPolicy policy) {
        return BusinessPolicyResponseDTO.builder()
                .id(policy.getId() != null ? policy.getId().toHexString() : null)
                .name(policy.getNombre())
                .description(policy.getDescripcion())
                .status(policy.getEstado())
                .version(policy.getVersion())
                .bpmnXml(policy.getBpmnXml())
                .startFormDefinition(formMapper.toDefinitionDTO(policy.getStartFormDefinition()))
                .startFormSchema(copyMapOrNull(policy.getStartFormSchema()))
                .createdAt(policy.getFechaCreacion())
                .updatedAt(policy.getFechaActualizacion())
                .build();
    }

    public void updateEntity(BusinessPolicy policy, BusinessPolicyRequestDTO dto) {
        policy.setNombre(dto.getName());
        policy.setDescripcion(dto.getDescription());
        // Only overwrite the stored XML when the caller actually provided one,
        // so plain field updates don't accidentally wipe the diagram.
        if (dto.getBpmnXml() != null) {
            policy.setBpmnXml(dto.getBpmnXml());
        }
        // Start form fields: treat "null" from the DTO as "don't touch" so a
        // metadata-only PATCH doesn't erase a previously configured start
        // form. To explicitly clear the form, callers can send an empty
        // FormDefinitionDTO (fields: []) instead of omitting the key.
        if (dto.getStartFormDefinition() != null) {
            policy.setStartFormDefinition(
                    ensureCustomerFields(formMapper.toEntity(dto.getStartFormDefinition())));
        }
        if (dto.getStartFormSchema() != null) {
            policy.setStartFormSchema(copyMapOrNull(dto.getStartFormSchema()));
        }
    }

    /**
     * Idempotently prepends the three customer-info fields (nombre, email,
     * CI) to the start form so every trámite captures the data the
     * consultor's "Consultas" lookup needs. If the admin already declared
     * a field with the same {@code name} we leave their definition alone
     * — the names are the contract; everything else (label, ordering) is
     * the admin's call.
     */
    private FormDefinition ensureCustomerFields(FormDefinition definition) {
        List<FormFieldDefinition> existing = definition == null || definition.getFields() == null
                ? new ArrayList<>()
                : new ArrayList<>(definition.getFields());

        Set<String> existingNames = new HashSet<>();
        for (FormFieldDefinition f : existing) {
            if (f != null && f.getName() != null) {
                existingNames.add(f.getName());
            }
        }

        List<FormFieldDefinition> reserved = new ArrayList<>();
        if (!existingNames.contains(CUSTOMER_NAME_FIELD)) {
            reserved.add(FormFieldDefinition.builder()
                    .name(CUSTOMER_NAME_FIELD)
                    .label("Nombre del cliente")
                    .type("text")
                    .required(true)
                    .build());
        }
        if (!existingNames.contains(CUSTOMER_EMAIL_FIELD)) {
            reserved.add(FormFieldDefinition.builder()
                    .name(CUSTOMER_EMAIL_FIELD)
                    .label("Correo del cliente")
                    .type("text")
                    .required(true)
                    .build());
        }
        if (!existingNames.contains(CUSTOMER_CI_FIELD)) {
            reserved.add(FormFieldDefinition.builder()
                    .name(CUSTOMER_CI_FIELD)
                    .label("Cédula / CI")
                    .type("text")
                    .required(true)
                    .build());
        }

        List<FormFieldDefinition> merged = new ArrayList<>(reserved.size() + existing.size());
        merged.addAll(reserved);
        merged.addAll(existing);
        return FormDefinition.builder().fields(merged).build();
    }

    private Map<String, Object> copyMapOrNull(Map<String, Object> source) {
        return source == null ? null : new HashMap<>(source);
    }
}
