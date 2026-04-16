package com.example.backend.controller;

import com.example.backend.dto.ActivityInstanceResponseDTO;
import com.example.backend.dto.CaseFileResponseDTO;
import com.example.backend.service.ActivityInstanceService;
import com.example.backend.service.WorkflowEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/activity-instances")
@RequiredArgsConstructor
public class ActivityInstanceController {

    private final ActivityInstanceService activityInstanceService;
    private final WorkflowEngineService workflowEngineService;

    /**
     * Lists activity instances.
     * Filters: userId (assigned activities), status (WAITING, IN_PROGRESS, COMPLETED),
     *          caseFileId (all instances for a case file).
     */
    @GetMapping
    public ResponseEntity<List<ActivityInstanceResponseDTO>> getActivities(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String caseFileId) {

        if (userId != null) {
            return ResponseEntity.ok(activityInstanceService.getActivitiesByUser(userId));
        }
        if (status != null) {
            return ResponseEntity.ok(activityInstanceService.getActivitiesByStatus(status));
        }
        if (caseFileId != null) {
            return ResponseEntity.ok(activityInstanceService.getActivitiesByCaseFile(caseFileId));
        }

        // Default: return all WAITING and IN_PROGRESS activities
        return ResponseEntity.ok(activityInstanceService.getActivitiesByStatus("WAITING"));
    }

    /** Starts an activity instance (WAITING -> IN_PROGRESS). */
    @PostMapping("/{id}/start")
    public ResponseEntity<ActivityInstanceResponseDTO> startActivity(
            @PathVariable String id,
            @RequestParam(required = false) String userId) {
        return ResponseEntity.ok(workflowEngineService.startActivity(id, userId));
    }

    /** Completes an activity instance and advances the workflow (IN_PROGRESS -> COMPLETED). */
    @PostMapping("/{id}/complete")
    public ResponseEntity<CaseFileResponseDTO> completeActivity(
            @PathVariable String id,
            @RequestParam(required = false) String userId) {
        return ResponseEntity.ok(workflowEngineService.completeActivity(id, userId));
    }
}
