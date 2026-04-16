package com.example.backend.mapper;

import com.example.backend.dto.RoleRequestDTO;
import com.example.backend.dto.RoleResponseDTO;
import com.example.backend.model.Role;
import org.springframework.stereotype.Component;

@Component
public class RoleMapper {

    public Role toEntity(RoleRequestDTO dto) {
        return Role.builder()
                .nombre(dto.getName())
                .descripcion(dto.getDescription())
                .build();
    }

    public RoleResponseDTO toResponse(Role role) {
        return RoleResponseDTO.builder()
                .id(role.getId() != null ? role.getId().toHexString() : null)
                .name(role.getNombre())
                .description(role.getDescripcion())
                .build();
    }

    public void updateEntity(Role role, RoleRequestDTO dto) {
        role.setNombre(dto.getName());
        role.setDescripcion(dto.getDescription());
    }
}
