package com.example.backend.controller;

import com.example.backend.dto.CaseStartFormDTO;
import com.example.backend.dto.OperatorTaskDTO;
import com.example.backend.dto.OperatorTasksResponseDTO;
import com.example.backend.service.OperatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoints for the operator Task Monitor UI (Phase 4).
 * Optimized for low-latency Kanban rendering with optional filters.
 */
@RestController
@RequestMapping("/api/operator")
@RequiredArgsConstructor
public class OperatorController {

    private final OperatorService operatorService;

    /**
     * Returns all tasks grouped by status for the 3-column Kanban view.
     *
     * Optional query params:
     *   userId — filter by the assigned user's id
     *   role   — filter by role NAME (all users with that role)
     *   lane   — filter by lane/department id (all activities in that lane)
     */
    @GetMapping("/tasks")
    public ResponseEntity<OperatorTasksResponseDTO> getTasks(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String lane) {
        return ResponseEntity.ok(operatorService.getOperatorTasks(userId, role, lane));
    }

    /**
     * Assigns a WAITING activity instance to a user atomically.
     * Fails with 400 if the activity is no longer WAITING or was already taken.
     */
    @PostMapping("/tasks/{id}/assign")
    public ResponseEntity<OperatorTaskDTO> assignTask(
            @PathVariable String id,
            @RequestParam String userId) {
        return ResponseEntity.ok(operatorService.assignActivity(id, userId));
    }

    /**
     * Customer-info panel for a task: returns the start form snapshot of the
     * trámite the task belongs to. The operator's task modal lazy-loads it
     * when the user clicks "Ver info. del cliente" so the regular Kanban
     * payload stays small.
     */
    @GetMapping("/cases/{caseId}/start-form")
    public ResponseEntity<CaseStartFormDTO> getCaseStartForm(@PathVariable String caseId) {
        return ResponseEntity.ok(operatorService.getCaseStartForm(caseId));
    }
}
