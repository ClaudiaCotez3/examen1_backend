package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Repositorio Documental — one trámite row inside a client's file. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientCaseDTO {

    private String id;
    private String code;
    /** activo | finalizado (raw backend status — normalized in the UI). */
    private String status;
    private String policyName;
    private long documentCount;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
