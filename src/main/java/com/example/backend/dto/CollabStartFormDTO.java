package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Real-time sync payload for the policy's start form. The start form is
 * metadata of the policy itself — not part of the BPMN diagram — so the
 * regular {@code workflow:*} attribute round-trip doesn't carry it. This
 * DTO is broadcast on a dedicated topic so every admin in the room ends
 * up with the same {@code startFormDefinition} / {@code startFormSchema}
 * pair the moment one of them changes it.
 *
 * Both fields are passed as opaque payloads:
 *   - {@code definition} mirrors the structured field list the runtime
 *     renderer consumes ({@code { fields: [...] }}).
 *   - {@code schema} is the form-js editor's source of truth, kept around
 *     so the builder can re-open the form without losing layout.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollabStartFormDTO {
    private String senderEmail;
    private Map<String, Object> definition;
    private Map<String, Object> schema;
    /**
     * Optional catalog entry id the form was applied from. Carried over the
     * wire so the remote sidebar can show the same human-friendly name in
     * its summary.
     */
    private String catalogId;
    /** Display name resolved by the sender — peer fallback when set. */
    private String displayName;
}
