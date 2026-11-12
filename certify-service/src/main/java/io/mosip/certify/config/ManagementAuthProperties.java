package io.mosip.certify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Trust settings for the administrative endpoints (revocation and ledger search). They are independent of the
 * wallet and pre-authorization trust settings.
 */
@ConfigurationProperties("mosip.certify.management.auth")
public class ManagementAuthProperties {

    /** Turns bearer-token protection on for the administrative endpoints. */
    private boolean enabled = false;
    private String issuer;
    private String jwkSetUri;
    private String audience;
    /** Client identities allowed to call the administrative endpoints. */
    private List<String> allowedClients = new ArrayList<>();
    /** Token claim holding the client identity. No fallback to other claims is applied. */
    private String clientIdClaim = "client_id";
    /** Leaves the endpoints open when auth is disabled. For local development only. */
    private boolean allowUnauthenticated = false;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public List<String> getAllowedClients() { return allowedClients; }
    public void setAllowedClients(List<String> allowedClients) { this.allowedClients = allowedClients; }
    public String getClientIdClaim() { return clientIdClaim; }
    public void setClientIdClaim(String clientIdClaim) { this.clientIdClaim = clientIdClaim; }
    public boolean isAllowUnauthenticated() { return allowUnauthenticated; }
    public void setAllowUnauthenticated(boolean allowUnauthenticated) { this.allowUnauthenticated = allowUnauthenticated; }

    /** Fails startup when auth is enabled but its settings are incomplete. */
    public void validate() {
        if (!enabled) {
            return;
        }
        require(issuer, "mosip.certify.management.auth.issuer");
        require(jwkSetUri, "mosip.certify.management.auth.jwk-set-uri");
        require(audience, "mosip.certify.management.auth.audience");
        require(clientIdClaim, "mosip.certify.management.auth.client-id-claim");
        if (allowedClients == null || allowedClients.stream().noneMatch(c -> c != null && !c.isBlank())) {
            throw new IllegalStateException("mosip.certify.management.auth.allowed-clients must list at least one client");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when management authentication is enabled");
        }
    }
}
