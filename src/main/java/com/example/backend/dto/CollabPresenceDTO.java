package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Snapshot of who is currently editing a given policy. Pushed to the room
 * topic whenever an admin joins or leaves so every client renders the
 * same presence header.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CollabPresenceDTO {
    private String policyId;
    private List<String> emails;
}
