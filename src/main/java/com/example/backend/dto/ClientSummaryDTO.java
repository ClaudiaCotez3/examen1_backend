package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Repositorio Documental — one client row of the admin's client list.
 * The special id {@code "unidentified"} groups legacy trámites that carry
 * no identifiable customer data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClientSummaryDTO {

    private String id;
    private String name;
    private String email;
    private String ci;
    private long caseCount;
    private long documentCount;
    private LocalDateTime lastCaseAt;
}
