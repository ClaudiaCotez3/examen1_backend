package com.example.backend.service;

import com.example.backend.dto.PolicyVersionResponseDTO;
import com.example.backend.exception.BadRequestException;
import com.example.backend.exception.ResourceNotFoundException;
import com.example.backend.mapper.PolicyVersionMapper;
import com.example.backend.model.PolicyVersion;
import com.example.backend.repository.BusinessPolicyRepository;
import com.example.backend.repository.PolicyVersionRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PolicyVersionService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";

    private final PolicyVersionRepository policyVersionRepository;
    private final BusinessPolicyRepository policyRepository;
    private final PolicyVersionMapper policyVersionMapper;

    public PolicyVersionResponseDTO createVersion(String policyId) {
        ObjectId policyObjectId = parseObjectId(policyId, "policyId");
        if (!policyRepository.existsById(policyObjectId)) {
            throw new ResourceNotFoundException("BusinessPolicy", policyId);
        }

        List<PolicyVersion> existing = policyVersionRepository.findByPoliticaIdOrderByNumeroVersionDesc(policyObjectId);
        int nextNumber = existing.isEmpty() ? 1 : existing.get(0).getNumeroVersion() + 1;

        PolicyVersion version = PolicyVersion.builder()
                .politicaId(policyObjectId)
                .numeroVersion(nextNumber)
                .estado(STATUS_INACTIVE)
                .fechaPublicacion(LocalDateTime.now())
                .build();

        PolicyVersion saved = policyVersionRepository.save(version);
        return policyVersionMapper.toResponse(saved);
    }

    public PolicyVersionResponseDTO activateVersion(String versionId) {
        ObjectId id = parseObjectId(versionId, "versionId");
        PolicyVersion target = policyVersionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("PolicyVersion", versionId));

        policyVersionRepository.findByPoliticaIdAndEstado(target.getPoliticaId(), STATUS_ACTIVE)
                .ifPresent(current -> {
                    if (!current.getId().equals(target.getId())) {
                        current.setEstado(STATUS_INACTIVE);
                        policyVersionRepository.save(current);
                    }
                });

        target.setEstado(STATUS_ACTIVE);
        PolicyVersion saved = policyVersionRepository.save(target);
        return policyVersionMapper.toResponse(saved);
    }

    public List<PolicyVersionResponseDTO> getVersionsByPolicy(String policyId) {
        ObjectId policyObjectId = parseObjectId(policyId, "policyId");
        return policyVersionRepository.findByPoliticaIdOrderByNumeroVersionDesc(policyObjectId).stream()
                .map(policyVersionMapper::toResponse)
                .toList();
    }

    private ObjectId parseObjectId(String value, String field) {
        if (value == null || !ObjectId.isValid(value)) {
            throw new BadRequestException("Invalid " + field + ": " + value);
        }
        return new ObjectId(value);
    }
}
