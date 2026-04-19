package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Outbound representation of a stored form submission.
 *
 * Example:
 * <pre>
 * {
 *   "activityInstanceId": "...",
 *   "formData": {
 *     "customerName": "John",
 *     "installationDate": "2026-01-01"
 *   }
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FormResponseDTO {

    private String id;
    private String activityInstanceId;
    private Map<String, Object> formData;
    private String submittedBy;
    private LocalDateTime submittedAt;
}
