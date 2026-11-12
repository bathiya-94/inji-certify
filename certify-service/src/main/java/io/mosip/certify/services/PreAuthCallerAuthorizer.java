package io.mosip.certify.services;
import io.mosip.certify.config.PreAuthServiceAuthProperties;
import java.util.Set;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

public class PreAuthCallerAuthorizer {
    private final PreAuthServiceAuthProperties properties;
    public PreAuthCallerAuthorizer(PreAuthServiceAuthProperties properties) { this.properties = properties; }
    public void authorize(String credentialId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Set<String> clients = properties.getAllowedClients().getOrDefault(credentialId, Set.of());
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Jwt jwt)
                || !org.springframework.util.StringUtils.hasText(jwt.getClaimAsString("client_id"))
                || !clients.contains(jwt.getClaimAsString("client_id"))
                || authentication.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("SCOPE_certify:preauth:create")))
            throw new AccessDeniedException("Caller is not permitted to create this credential offer");
    }
}
