package io.mosip.certify.services;

import io.mosip.certify.config.ManagementAuthProperties;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 * Decides whether an already validated bearer token may call an administrative endpoint: the configured
 * client-identity claim must name an allowed client and the token must carry the endpoint's scope.
 * The client identity is never taken from any other claim.
 */
public class ManagementCallerAuthorizer {

    private final ManagementAuthProperties properties;

    public ManagementCallerAuthorizer(ManagementAuthProperties properties) {
        this.properties = properties;
    }

    public AuthorizationManager<RequestAuthorizationContext> requiring(String scope) {
        return (authentication, context) -> new AuthorizationDecision(permits(authentication.get(), scope));
    }

    boolean permits(Authentication authentication, String scope) {
        if (!(authentication instanceof JwtAuthenticationToken token) || !authentication.isAuthenticated()) {
            return false;
        }
        Object claim = token.getToken().getClaims().get(properties.getClientIdClaim());
        if (!(claim instanceof String client) || client.isBlank() || !properties.getAllowedClients().contains(client)) {
            return false;
        }
        String authority = "SCOPE_" + scope;
        return authentication.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
