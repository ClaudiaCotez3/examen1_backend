package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Repositorio Documental — top-level payload of the admin module:
 * global KPIs + the (optionally filtered) client list.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepositoryOverviewDTO {

    private long totalClients;
    private long totalCases;
    private long totalDocuments;
    private List<ClientSummaryDTO> clients;
}
