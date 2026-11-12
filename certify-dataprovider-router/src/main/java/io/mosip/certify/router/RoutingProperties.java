package io.mosip.certify.router;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties("mosip.certify.integration.data-provider-routing")
public class RoutingProperties {
    private Map<String, String> credentialMapping = new LinkedHashMap<>();
    public Map<String, String> getCredentialMapping() { return credentialMapping; }
    public void setCredentialMapping(Map<String, String> mapping) { credentialMapping = mapping; }
}
