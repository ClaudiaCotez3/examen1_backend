package com.example.backend.controller;

import com.example.backend.dto.BottleneckActivityDTO;
import com.example.backend.dto.OperatorPerformanceDTO;
import com.example.backend.dto.SupervisorOverviewDTO;
import com.example.backend.service.SupervisorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only KPI endpoints for the supervisor dashboard. Live under
 * {@code /api/supervisor/**}, which the security config already gates
 * to SUPERVISOR / ADMIN.
 */
@RestController
@RequestMapping("/api/supervisor")
@RequiredArgsConstructor
public class SupervisorController {

    private final SupervisorService supervisorService;

    @GetMapping("/overview")
    public ResponseEntity<SupervisorOverviewDTO> getOverview() {
        return ResponseEntity.ok(supervisorService.getOverview());
    }

    @GetMapping("/bottlenecks")
    public ResponseEntity<List<BottleneckActivityDTO>> getBottlenecks() {
        return ResponseEntity.ok(supervisorService.getBottlenecksByActivity());
    }

    @GetMapping("/operators")
    public ResponseEntity<List<OperatorPerformanceDTO>> getOperators() {
        return ResponseEntity.ok(supervisorService.getOperatorPerformance());
    }
}
