package com.example.backend.service;

import com.example.backend.dto.ActivityFormDTO;
import com.example.backend.dto.FormResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.FormMapper;
import com.example.backend.model.Activity;
import com.example.backend.model.ActivityInstance;
import com.example.backend.model.FormDefinition;
import com.example.backend.model.FormFieldDefinition;
import com.example.backend.model.FormResponse;
import com.example.backend.repository.ActivityInstanceRepository;
import com.example.backend.repository.ActivityRepository;
import com.example.backend.repository.FormResponseRepository;
import com.example.backend.security.CustomUserDetails;
import com.example.backend.security.RoleName;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coordinates everything related to dynamic forms:
 *   - exposing the schema attached to an activity
 *   - validating + persisting submissions
 *   - retrieving previously submitted responses
 *
 * Validation, ownership checks and persistence all live here so that
 * controllers stay thin and the {@link com.example.backend.service.WorkflowEngineService}
 * can delegate the "form-required" precondition through a single call
 * ({@link #hasResponse(ObjectId)}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormService {

    /** Roles allowed to manipulate forms at all. ADMIN included for admin tooling. */
    private static final Set<String> ALLOWED_ROLES = Set.of(
            RoleName.OPERATOR, RoleName.SUPERVISOR, RoleName.ADMIN);

    private final ActivityRepository activityRepository;
    private final ActivityInstanceRepository activityInstanceRepository;
    private final FormResponseRepository formResponseRepository;
    private final FormMapper formMapper;

    // ── Schema lookup ───────────────────────────────────────────────────────

    /**
     * Returns the form schema attached to an activity (TASK 3.1).
     * Throws 404 if the activity does not exist, 400 if it has no form.
     */
    public ActivityFormDTO getFormByActivity(String activityId) {
        Activity activity = loadActivity(activityId);
        if (activity.getFormDefinition() == null
                || activity.getFormDefinition().getFields() == null
                || activity.getFormDefinition().getFields().isEmpty()) {
            throw new BadRequestException("Activity " + activityId + " has no form definition");
        }
        return formMapper.toActivityForm(activity);
    }

    // ── Submission ──────────────────────────────────────────────────────────

    /**
     * Validates and persists a form submission for the given activity instance
     * (TASK 3.2). Enforces:
     *   - activity must declare a form schema
     *   - submitter must be the assigned operator (TASK 8)
     *   - required fields and value types must match the schema (TASK 4)
     *   - one submission per activity instance (idempotent rejection on retry)
     */
    public FormResponseDTO submitForm(String activityInstanceId,
                                      Map<String, Object> formData,
                                      CustomUserDetails caller) {
        if (formData == null) {
            throw new BadRequestException("formData is required");
        }
        assertRoleAllowed(caller);

        ActivityInstance instance = loadInstance(activityInstanceId);
        Activity activity = activityRepository.findById(instance.getActividadId())
                .orElseThrow(() -> new ResourceNotFoundException("Activity",
                        instance.getActividadId().toHexString()));

        FormDefinition definition = activity.getFormDefinition();
        if (definition == null || definition.getFields() == null || definition.getFields().isEmpty()) {
            throw new BadRequestException(
                    "Activity " + activity.getId() + " has no form to submit");
        }

        assertCallerIsAssignee(instance, caller);
        validateFormData(definition, formData);

        if (formResponseRepository.existsByInstanciaActividadId(instance.getId())) {
            throw new BadRequestException(
                    "A form has already been submitted for this activity instance");
        }

        FormResponse entity = FormResponse.builder()
                .instanciaActividadId(instance.getId())
                .data(formData)
                .submittedBy(parseObjectId(caller.getId(), "userId"))
                .submittedAt(LocalDateTime.now())
                .build();
        FormResponse saved = formResponseRepository.save(entity);
        log.info("Form submitted: activityInstance={}, by={}, fields={}",
                instance.getId(), caller.getId(), formData.keySet().size());
        return formMapper.toResponseDTO(saved);
    }

    // ── Read submission ─────────────────────────────────────────────────────

    /** Returns the stored submission for an activity instance (TASK 3.3). 404 if none. */
    public FormResponseDTO getFormResponse(String activityInstanceId, CustomUserDetails caller) {
        assertRoleAllowed(caller);
        ObjectId instanceId = parseObjectId(activityInstanceId, "activityInstanceId");
        FormResponse entity = formResponseRepository.findByInstanciaActividadId(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "FormResponse for activityInstance", activityInstanceId));
        return formMapper.toResponseDTO(entity);
    }

    /**
     * Cheap precondition used by the workflow engine to gate completeActivity
     * when the activity has {@code requiereFormulario = true} (TASK 5).
     */
    public boolean hasResponse(ObjectId activityInstanceId) {
        return formResponseRepository.existsByInstanciaActividadId(activityInstanceId);
    }

    // ── Validation ──────────────────────────────────────────────────────────

    /**
     * Runs the structural checks that protect the database from malformed
     * submissions (TASK 4). Rejects on the first violation and returns a
     * descriptive {@link BadRequestException} so the UI can highlight the
     * offending field.
     *
     * Exposed as public so the workflow engine can reuse it to validate the
     * policy-level start form when a case is opened.
     */
    public void validateFormData(FormDefinition definition, Map<String, Object> formData) {
        List<String> errors = new ArrayList<>();

        Set<String> known = new java.util.HashSet<>();
        for (FormFieldDefinition field : definition.getFields()) {
            known.add(field.getName());
            Object value = formData.get(field.getName());

            boolean missing = value == null
                    || (value instanceof String s && s.isBlank());
            if (Boolean.TRUE.equals(field.getRequired()) && missing) {
                errors.add("Field '" + field.getName() + "' is required");
                continue;
            }
            if (missing) {
                continue; // optional + absent → OK
            }
            String typeError = validateType(field, value);
            if (typeError != null) {
                errors.add(typeError);
            }
        }

        // Reject unknown fields to keep the document predictable.
        for (String key : formData.keySet()) {
            if (!known.contains(key)) {
                errors.add("Unknown field '" + key + "' is not part of the form definition");
            }
        }

        if (!errors.isEmpty()) {
            throw new BadRequestException("Invalid form submission: " + String.join("; ", errors));
        }
    }

    private String validateType(FormFieldDefinition field, Object value) {
        String type = field.getType() == null ? "text" : field.getType().toLowerCase();
        switch (type) {
            case "text", "file" -> {
                if (!(value instanceof String)) {
                    return "Field '" + field.getName() + "' must be a string";
                }
            }
            case "number" -> {
                if (!(value instanceof Number)) {
                    if (value instanceof String s) {
                        try {
                            Double.parseDouble(s);
                        } catch (NumberFormatException ex) {
                            return "Field '" + field.getName() + "' must be a number";
                        }
                    } else {
                        return "Field '" + field.getName() + "' must be a number";
                    }
                }
            }
            case "boolean" -> {
                if (!(value instanceof Boolean)
                        && !(value instanceof String s
                            && (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")))) {
                    return "Field '" + field.getName() + "' must be a boolean";
                }
            }
            case "date" -> {
                if (!(value instanceof String s)) {
                    return "Field '" + field.getName() + "' must be an ISO-8601 date string";
                }
                try {
                    LocalDate.parse(s);
                } catch (DateTimeParseException ex) {
                    return "Field '" + field.getName()
                            + "' must follow yyyy-MM-dd";
                }
            }
            case "select" -> {
                if (!(value instanceof String s)) {
                    return "Field '" + field.getName() + "' must be a string";
                }
                if (field.getOptions() != null && !field.getOptions().isEmpty()
                        && !field.getOptions().contains(s)) {
                    return "Field '" + field.getName() + "' must be one of " + field.getOptions();
                }
            }
            default -> {
                // Unknown declared type: accept as-is rather than crashing.
                log.debug("Unknown field type '{}' for field {}, accepting raw value",
                        type, field.getName());
            }
        }
        return null;
    }

    // ── Security / lookup helpers ───────────────────────────────────────────

    /** Caller must own the activity instance unless they are SUPERVISOR/ADMIN. */
    private void assertCallerIsAssignee(ActivityInstance instance, CustomUserDetails caller) {
        if (caller == null) {
            throw new BadRequestException("Authenticated user required");
        }
        if (RoleName.ADMIN.equals(caller.getRoleName())
                || RoleName.SUPERVISOR.equals(caller.getRoleName())) {
            return; // privileged roles can submit on behalf of operators
        }
        ObjectId callerId = parseObjectId(caller.getId(), "userId");
        if (instance.getClaimedBy() == null || !instance.getClaimedBy().equals(callerId)) {
            throw new BadRequestException(
                    "Only the operator who claimed this task can submit its form");
        }
    }

    private void assertRoleAllowed(CustomUserDetails caller) {
        if (caller == null || caller.getRoleName() == null
                || !ALLOWED_ROLES.contains(caller.getRoleName())) {
            throw new BadRequestException("User role is not allowed to access forms");
        }
    }

    private Activity loadActivity(String activityId) {
        ObjectId id = parseObjectId(activityId, "activityId");
        return activityRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Activity", activityId));
    }

    private ActivityInstance loadInstance(String activityInstanceId) {
        ObjectId id = parseObjectId(activityInstanceId, "activityInstanceId");
        return activityInstanceRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ActivityInstance", activityInstanceId));
    }

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
