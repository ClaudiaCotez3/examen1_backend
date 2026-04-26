package com.example.backend.service;

import com.example.backend.exception.BadRequestException;
import com.example.backend.model.FormDefinition;
import com.example.backend.model.FormFieldDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Validates the {@code startFormData} payload submitted by the consultor
 * against the {@link FormDefinition} authored by the admin on the target
 * {@link com.example.backend.model.BusinessPolicy}.
 *
 * Scope: top-level scalar checks only.
 *   - Required fields must be present and non-empty.
 *   - {@code type = "number"} values must be numeric (either a number
 *     primitive or a string that parses as one — the frontend often sends
 *     input-bound numbers as strings).
 *   - {@code type = "checkbox" | "boolean"} values must be booleans.
 *   - {@code type = "select" | "radio"} values must belong to
 *     {@link FormFieldDefinition#getOptions()} when options are declared.
 *   - {@code type = "file"} values must look like a list of metadata
 *     objects (the frontend sends {@code [{ name, size, type }]}).
 *
 * Complex types (group / dynamic-list) are only checked for structural
 * shape (map / list respectively) — recursing into their children would
 * duplicate logic the frontend already enforces via reactive validators
 * and would need richer type hints to be useful. We accept whatever lands
 * on those fields and persist it verbatim.
 */
@Component
public class StartFormValidator {

    /**
     * Runs the checks and throws {@link BadRequestException} with a
     * human-readable summary when anything fails. Returning silently on
     * success keeps the call site ergonomic.
     *
     * @param definition form schema to validate against (null / empty =
     *                   everything is allowed, including null data)
     * @param data       submitted values keyed by field name (may be null)
     */
    public void validate(FormDefinition definition, Map<String, Object> data) {
        if (definition == null
                || definition.getFields() == null
                || definition.getFields().isEmpty()) {
            return;
        }

        Map<String, Object> safeData = data == null ? Map.of() : data;
        List<String> errors = new ArrayList<>();

        for (FormFieldDefinition field : definition.getFields()) {
            validateField(field, safeData.get(field.getName()), errors);
        }

        if (!errors.isEmpty()) {
            throw new BadRequestException(
                    "Invalid start form data: " + String.join("; ", errors));
        }
    }

    private void validateField(FormFieldDefinition field, Object value, List<String> errors) {
        String fieldName = field.getName();
        boolean required = Boolean.TRUE.equals(field.getRequired());
        boolean present = isPresent(value);

        if (required && !present) {
            errors.add("'" + fieldName + "' is required");
            return;
        }
        if (!present) {
            return;
        }

        String type = field.getType();
        if (type == null || type.isBlank()) {
            return;
        }

        switch (type.toLowerCase()) {
            case "number" -> {
                if (!isNumeric(value)) {
                    errors.add("'" + fieldName + "' must be a number");
                }
            }
            case "boolean", "checkbox" -> {
                if (!(value instanceof Boolean)) {
                    errors.add("'" + fieldName + "' must be a boolean");
                }
            }
            case "select", "radio" -> {
                List<String> options = field.getOptions();
                if (options != null && !options.isEmpty()
                        && !options.contains(String.valueOf(value))) {
                    errors.add("'" + fieldName + "' must be one of: "
                            + String.join(", ", options));
                }
            }
            case "file" -> {
                if (!(value instanceof Collection<?>)) {
                    errors.add("'" + fieldName + "' must be a list of files");
                }
            }
            case "tags" -> {
                if (!(value instanceof Collection<?>)) {
                    errors.add("'" + fieldName + "' must be a list of tags");
                }
            }
            case "group" -> {
                if (!(value instanceof Map<?, ?>)) {
                    errors.add("'" + fieldName + "' must be an object");
                }
            }
            case "dynamic-list" -> {
                if (!(value instanceof Collection<?>)) {
                    errors.add("'" + fieldName + "' must be a list");
                }
            }
            // text / textarea / date / datetime: accept as-is
            default -> { /* permissive by design */ }
        }
    }

    private boolean isPresent(Object value) {
        if (value == null) return false;
        if (value instanceof String s) return !s.isBlank();
        if (value instanceof Collection<?> c) return !c.isEmpty();
        if (value instanceof Map<?, ?> m) return !m.isEmpty();
        return true;
    }

    private boolean isNumeric(Object value) {
        if (value instanceof Number) return true;
        if (value instanceof String s) {
            try {
                Double.parseDouble(s);
                return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }
}
