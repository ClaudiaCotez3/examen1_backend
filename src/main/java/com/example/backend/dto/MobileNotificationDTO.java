package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Portal móvil — una notificación del cliente (campanita e historial). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MobileNotificationDTO {

    private String id;
    private String caseId;
    private String caseCode;
    /** CASE_STARTED | AREA_CHANGED | CASE_FINISHED */
    private String type;
    private String title;
    private String message;
    private LocalDateTime createdAt;
    private boolean read;
}
