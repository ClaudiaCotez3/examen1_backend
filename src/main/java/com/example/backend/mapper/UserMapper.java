package com.example.backend.mapper;

import com.example.backend.dto.UserRequestDTO;
import com.example.backend.dto.UserResponseDTO;
import com.example.backend.model.User;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

/**
 * Pure structural mapping only. Password hashing is handled in UserService
 * because mappers should not depend on security concerns.
 */
@Component
public class UserMapper {

    public User toEntity(UserRequestDTO dto) {
        return User.builder()
                .nombre(dto.getName())
                .email(dto.getEmail())
                .rolId(new ObjectId(dto.getRoleId()))
                .build();
    }

    public UserResponseDTO toResponse(User user) {
        return UserResponseDTO.builder()
                .id(user.getId() != null ? user.getId().toHexString() : null)
                .name(user.getNombre())
                .email(user.getEmail())
                .roleId(user.getRolId() != null ? user.getRolId().toHexString() : null)
                .active(user.getActivo())
                .createdAt(user.getFechaCreacion())
                .build();
    }

    public void updateEntity(User user, UserRequestDTO dto) {
        user.setNombre(dto.getName());
        user.setEmail(dto.getEmail());
        user.setRolId(new ObjectId(dto.getRoleId()));
    }
}
