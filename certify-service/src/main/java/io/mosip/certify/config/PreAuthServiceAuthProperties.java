package io.mosip.certify.config;
import java.util.*;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
@ConfigurationProperties("mosip.certify.pre-auth.service-auth")
@Validated
public class PreAuthServiceAuthProperties {
    @NotBlank private String issuer;
    @NotBlank private String jwkSetUri;
    @NotBlank private String audience;
    @NotEmpty private Map<String, Set<String>> allowedClients = new LinkedHashMap<>();
    public String getIssuer() { return issuer; }
    public void setIssuer(String value) { issuer = value; }
    public String getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String value) { jwkSetUri = value; }
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public Map<String, Set<String>> getAllowedClients() { return allowedClients; }
    public void setAllowedClients(Map<String, Set<String>> value) { allowedClients = value; }
}
