package com.example.backend.controller;

import com.example.backend.dto.LaneRequestDTO;
import com.example.backend.dto.LaneResponseDTO;
import com.example.backend.service.LaneService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/lanes")
@RequiredArgsConstructor
public class LaneController {

    private final LaneService laneService;

    @PostMapping
    public ResponseEntity<LaneResponseDTO> create(@RequestParam String policyId,
                                                  @Valid @RequestBody LaneRequestDTO request) {
        LaneResponseDTO response = laneService.createLane(policyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<LaneResponseDTO>> getByPolicy(@RequestParam String policyId) {
        return ResponseEntity.ok(laneService.getLanesByPolicy(policyId));
    }
}
