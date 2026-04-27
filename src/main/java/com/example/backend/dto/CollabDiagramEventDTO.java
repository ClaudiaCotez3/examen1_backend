package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Full BPMN XML snapshot broadcast across collaborators every time the
 * local modeler emits a change. Carries the sender email so the receiver
 * can ignore its own echo (the broadcast is fan-out, not point-to-point).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollabDiagramEventDTO {
    private String senderEmail;
    private String xml;
}
