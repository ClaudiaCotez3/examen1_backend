package com.example.backend.controller;

import com.example.backend.dto.ActivityFormDTO;
import com.example.backend.dto.FormResponseDTO;
import com.example.backend.dto.FormSubmissionRequestDTO;
import com.example.backend.security.CustomUserDetails;
import com.example.backend.security.RoleName;
import com.example.backend.service.FormService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the dynamic-forms feature (Phase 5).
 *
 * Routes:
 *   GET  /api/forms/activity/{activityId}            — fetch the schema attached to an activity
 *   POST /api/forms/submit/{activityInstanceId}      — submit a filled form
 *   GET  /api/forms/response/{activityInstanceId}    — read back a previously submitted form
 *
 * Authorization is enforced both at the URL level
 * (see {@link com.example.backend.config.SecurityConfig}) and at the method
 * level via {@link PreAuthorize}, plus owner-based checks inside the service.
 */
@RestController
@RequestMapping("/api/forms")
@RequiredArgsConstructor
public class FormController {

    private final FormService formService;

    /** Form schema for an activity — used by the runtime UI to render the form. */
    @GetMapping("/activity/{activityId}")
    @PreAuthorize("hasAnyRole('" + RoleName.OPERATOR + "', '" + RoleName.SUPERVISOR
            + "', '" + RoleName.ADMIN + "')")
    public ResponseEntity<ActivityFormDTO> getFormByActivity(@PathVariable String activityId) {
        return ResponseEntity.ok(formService.getFormByActivity(activityId));
    }

    /** Submit a completed form. Caller must be the assigned operator (or supervisor/admin). */
    @PostMapping("/submit/{activityInstanceId}")
    @PreAuthorize("hasAnyRole('" + RoleName.OPERATOR + "', '" + RoleName.SUPERVISOR
            + "', '" + RoleName.ADMIN + "')")
    public ResponseEntity<FormResponseDTO> submitForm(
            @PathVariable String activityInstanceId,
            @Valid @RequestBody FormSubmissionRequestDTO request,
            @AuthenticationPrincipal CustomUserDetails caller) {
        FormResponseDTO response = formService.submitForm(
                activityInstanceId, request.getFormData(), caller);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** Retrieves the previously submitted form for an activity instance. */
    @GetMapping("/response/{activityInstanceId}")
    @PreAuthorize("hasAnyRole('" + RoleName.OPERATOR + "', '" + RoleName.SUPERVISOR
            + "', '" + RoleName.ADMIN + "', '" + RoleName.CONSULTATION + "')")
    public ResponseEntity<FormResponseDTO> getFormResponse(
            @PathVariable String activityInstanceId,
            @AuthenticationPrincipal CustomUserDetails caller) {
        return ResponseEntity.ok(formService.getFormResponse(activityInstanceId, caller));
    }
}
