package com.example.backend.service;

import com.example.backend.dto.RoleRequestDTO;
import com.example.backend.dto.RoleResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.RoleMapper;
import com.example.backend.model.Role;
import com.example.backend.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RoleService {

    private final RoleRepository roleRepository;
    private final RoleMapper roleMapper;

    public RoleResponseDTO create(RoleRequestDTO request) {
        roleRepository.findByNombre(request.getName()).ifPresent(r -> {
            throw new BadRequestException("Role name already exists: " + request.getName());
        });

        Role role = roleMapper.toEntity(request);
        Role saved = roleRepository.save(role);
        return roleMapper.toResponse(saved);
    }

    public List<RoleResponseDTO> getAll() {
        return roleRepository.findAll().stream()
                .map(roleMapper::toResponse)
                .toList();
    }

    public RoleResponseDTO getById(String id) {
        Role role = findRoleOrThrow(id);
        return roleMapper.toResponse(role);
    }

    public RoleResponseDTO update(String id, RoleRequestDTO request) {
        Role role = findRoleOrThrow(id);

        if (!role.getNombre().equalsIgnoreCase(request.getName())) {
            roleRepository.findByNombre(request.getName()).ifPresent(r -> {
                throw new BadRequestException("Role name already exists: " + request.getName());
            });
        }

        roleMapper.updateEntity(role, request);
        Role saved = roleRepository.save(role);
        return roleMapper.toResponse(saved);
    }

    /**
     * Physical delete. The Role entity has no `active` flag per the domain model,
     * so a logical delete is not applicable here.
     */
    public void delete(String id) {
        Role role = findRoleOrThrow(id);
        roleRepository.delete(role);
    }

    private Role findRoleOrThrow(String id) {
        if (!ObjectId.isValid(id)) {
            throw new BadRequestException("Invalid id: " + id);
        }
        return roleRepository.findById(new ObjectId(id))
                .orElseThrow(() -> new ResourceNotFoundException("Role", id));
    }
}
