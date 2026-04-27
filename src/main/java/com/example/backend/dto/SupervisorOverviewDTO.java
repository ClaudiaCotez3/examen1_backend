package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Top-of-page KPIs for the supervisor dashboard: a quick read of how
 * the workflow engine is doing right now.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SupervisorOverviewDTO {
    private long activeCases;
    private long completedCases;
    private long pendingTasks;
    private long inProgressTasks;
    /** P95 of all completed instances' lead time, in minutes. */
    private double p95LeadMinutes;
    /** Cases older than {@link #stalledDaysThreshold} days that are still active. */
    private long stalledCases;
    private int stalledDaysThreshold;
}
