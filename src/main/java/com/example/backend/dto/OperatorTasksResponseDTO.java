package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Groups operator tasks by status for the 3-column Kanban UI.
 * Wrapping in a single response avoids the frontend having to fire 3 HTTP calls.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperatorTasksResponseDTO {

    private List<OperatorTaskDTO> waiting;
    private List<OperatorTaskDTO> inProgress;
    private List<OperatorTaskDTO> completed;
}
