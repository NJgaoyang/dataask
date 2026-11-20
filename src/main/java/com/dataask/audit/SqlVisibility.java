package com.dataask.audit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.security.core.Authentication;

/** Shared visibility policy for live, historical and shared-chart answers. */
public final class SqlVisibility {
    private SqlVisibility() {}
    public static boolean admin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a->"ROLE_ADMIN".equals(a.getAuthority()));
    }
    public static JsonNode visible(JsonNode payload, Authentication auth) {
        // The generated SQL is part of the user-facing analysis explanation.
        // Data access is still enforced before execution by SqlGuard; this method
        // only controls which already-authorized answer fields are returned.
        return payload;
    }
}
