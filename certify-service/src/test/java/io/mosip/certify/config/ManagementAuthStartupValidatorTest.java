package io.mosip.certify.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ManagementAuthStartupValidatorTest {

    private static ManagementAuthProperties properties(boolean enabled, boolean allowUnauthenticated) {
        ManagementAuthProperties properties = new ManagementAuthProperties();
        properties.setEnabled(enabled);
        properties.setAllowUnauthenticated(allowUnauthenticated);
        if (enabled) {
            properties.setIssuer("https://idp");
            properties.setJwkSetUri("https://idp/jwks");
            properties.setAudience("certify-management");
            properties.setAllowedClients(List.of("admin"));
        }
        return properties;
    }

    private static void validate(ManagementAuthProperties properties, boolean ledger, List<String> purposes) {
        new ManagementAuthStartupValidator(properties, ledger, purposes).validate();
    }

    @Test
    void disabledWithLedger_refusesToStart() {
        assertThrows(IllegalStateException.class, () -> validate(properties(false, false), true, List.of()));
    }

    @Test
    void disabledWithStatusPurposes_refusesToStart() {
        assertThrows(IllegalStateException.class, () -> validate(properties(false, false), false, List.of("revocation")));
    }

    @Test
    void explicitOverride_allowsStart() {
        assertDoesNotThrow(() -> validate(properties(false, true), true, List.of("revocation")));
    }

    @Test
    void nothingToProtect_allowsStart() {
        assertDoesNotThrow(() -> validate(properties(false, false), false, List.of()));
    }

    @Test
    void enabledAndComplete_allowsStart() {
        assertDoesNotThrow(() -> validate(properties(true, false), true, List.of("revocation")));
    }

    @Test
    void enabledButIncomplete_refusesToStart() {
        for (int missing = 0; missing < 5; missing++) {
            ManagementAuthProperties properties = properties(true, false);
            switch (missing) {
                case 0 -> properties.setIssuer(" ");
                case 1 -> properties.setJwkSetUri(null);
                case 2 -> properties.setAudience("");
                case 3 -> properties.setAllowedClients(List.of());
                default -> properties.setClientIdClaim(" ");
            }
            assertThrows(IllegalStateException.class, () -> validate(properties, true, List.of()), "case " + missing);
        }
    }
}
