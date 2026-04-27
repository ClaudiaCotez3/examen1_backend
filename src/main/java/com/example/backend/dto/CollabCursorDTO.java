package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cursor position broadcast to the collaboration room. Coordinates are in
 * diagram space (bpmn-js logical pixels) so each remote client can render
 * them independently of its current zoom / pan.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollabCursorDTO {
    private String senderEmail;
    private double x;
    private double y;
}
