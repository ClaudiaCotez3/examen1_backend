package com.example.backend.service;

import com.example.backend.dto.UserRequestDTO;
import com.example.backend.dto.UserResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.UserMapper;
import com.example.backend.model.Role;
import com.example.backend.model.User;
import com.example.backend.repository.RoleRepository;
import com.example.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserResponseDTO create(UserRequestDTO request) {
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new BadRequestException("Password is required when creating a user");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email is already registered: " + request.getEmail());
        }

        ObjectId roleId = parseObjectId(request.getRoleId(), "roleId");
        Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", request.getRoleId()));

        User user = userMapper.toEntity(request);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRolId(role.getId());
        user.setActivo(true);
        user.setFechaCreacion(LocalDateTime.now());
        user.setFechaActualizacion(LocalDateTime.now());

        User saved = userRepository.save(user);
        return userMapper.toResponse(saved);
    }

    public List<UserResponseDTO> getAll() {
        return userRepository.findAll().stream()
                .map(userMapper::toResponse)
                .toList();
    }

    public UserResponseDTO getById(String id) {
        User user = findUserOrThrow(id);
        return userMapper.toResponse(user);
    }

    public UserResponseDTO update(String id, UserRequestDTO request) {
        User user = findUserOrThrow(id);

        if (!user.getEmail().equalsIgnoreCase(request.getEmail())
                && userRepository.existsByEmail(request.getEmail())) {
            throw new BadRequestException("Email is already registered: " + request.getEmail());
        }

        ObjectId roleId = parseObjectId(request.getRoleId(), "roleId");
        roleRepository.findById(roleId)
                .orElseThrow(() -> new ResourceNotFoundException("Role", request.getRoleId()));

        userMapper.updateEntity(user, request);

        if (request.getPassword() != null && !request.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(request.getPassword()));
        }
        user.setFechaActualizacion(LocalDateTime.now());

        User saved = userRepository.save(user);
        return userMapper.toResponse(saved);
    }

    /** Logical delete: flips `activo` to false instead of removing the document. */
    public void delete(String id) {
        User user = findUserOrThrow(id);
        if (Boolean.FALSE.equals(user.getActivo())) {
            throw new BadRequestException("User is already deactivated");
        }
        user.setActivo(false);
        user.setFechaActualizacion(LocalDateTime.now());
        userRepository.save(user);
    }

    private User findUserOrThrow(String id) {
        ObjectId objectId = parseObjectId(id, "id");
        return userRepository.findById(objectId)
                .orElseThrow(() -> new ResourceNotFoundException("User", id));
    }

    private ObjectId parseObjectId(String value, String field) {
        if (!ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
