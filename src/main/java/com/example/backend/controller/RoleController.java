package com.example.backend.controller;

import com.example.backend.dto.RoleRequestDTO;
import com.example.backend.dto.RoleResponseDTO;
import com.example.backend.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    @PostMapping
    public ResponseEntity<RoleResponseDTO> create(@Valid @RequestBody RoleRequestDTO request) {
        RoleResponseDTO response = roleService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<RoleResponseDTO>> getAll() {
        return ResponseEntity.ok(roleService.getAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RoleResponseDTO> getById(@PathVariable String id) {
        return ResponseEntity.ok(roleService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoleResponseDTO> update(@PathVariable String id,
                                                  @Valid @RequestBody RoleRequestDTO request) {
        return ResponseEntity.ok(roleService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        roleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
