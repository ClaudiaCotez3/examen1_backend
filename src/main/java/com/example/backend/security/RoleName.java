package com.example.backend.security;

/**
 * Canonical role names recognised by the security layer.
 * Mongo stores roles as free-form strings; these constants are the ones the
 * application relies on for authorization rules and seeding.
 */
public final class RoleName {

    public static final String ADMIN = "ADMIN";
    public static final String OPERATOR = "OPERATOR";
    public static final String CONSULTATION = "CONSULTATION";
    public static final String SUPERVISOR = "SUPERVISOR";

    public static final String[] ALL = { ADMIN, OPERATOR, CONSULTATION, SUPERVISOR };

    private RoleName() {
    }
}
