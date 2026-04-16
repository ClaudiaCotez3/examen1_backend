package com.example.backend.controller;

import com.example.backend.dto.ActivityRequestDTO;
import com.example.backend.dto.ActivityResponseDTO;
import com.example.backend.service.ActivityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/activities")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityService activityService;

    @PostMapping
    public ResponseEntity<ActivityResponseDTO> create(@RequestParam String policyId,
                                                      @Valid @RequestBody ActivityRequestDTO request) {
        ActivityResponseDTO response = activityService.createActivity(policyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<ActivityResponseDTO>> getByPolicy(@RequestParam String policyId) {
        return ResponseEntity.ok(activityService.getActivitiesByPolicy(policyId));
    }
}
