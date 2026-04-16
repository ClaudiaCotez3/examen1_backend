package com.example.backend.controller;

import com.example.backend.dto.FlowRequestDTO;
import com.example.backend.dto.FlowResponseDTO;
import com.example.backend.service.FlowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/flows")
@RequiredArgsConstructor
public class FlowController {

    private final FlowService flowService;

    @PostMapping
    public ResponseEntity<FlowResponseDTO> create(@Valid @RequestBody FlowRequestDTO request) {
        FlowResponseDTO response = flowService.createFlow(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<FlowResponseDTO>> getByPolicy(@RequestParam String policyId) {
        return ResponseEntity.ok(flowService.getFlowsByPolicy(policyId));
    }
}
