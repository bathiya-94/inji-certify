package io.mosip.certify.router;

import io.mosip.certify.api.exception.DataProviderExchangeException;
import io.mosip.certify.api.exception.LookupValidationException;
import io.mosip.certify.api.spi.*;
import org.json.JSONObject;
import java.util.*;

public class RouterDataProviderPlugin implements LookupDataProviderPlugin {
    private final RoutingProperties properties;
    private final Map<String, DataProviderRegistration> providers;

    public RouterDataProviderPlugin(RoutingProperties properties, List<DataProviderRegistration> registrations) {
        this.properties = properties;
        Map<String, DataProviderRegistration> registry = new LinkedHashMap<>();
        for (DataProviderRegistration registration : registrations) {
            if (registry.putIfAbsent(registration.id(), registration) != null)
                throw new IllegalStateException("Duplicate data provider registration: " + registration.id());
        }
        providers = Collections.unmodifiableMap(registry);
        if (properties.getCredentialMapping().isEmpty()) throw new IllegalStateException("Credential mappings are required");
        properties.getCredentialMapping().keySet().forEach(this::resolve);
    }

    private DataProviderRegistration resolve(String credentialId) {
        String providerId = properties.getCredentialMapping().get(credentialId);
        DataProviderRegistration registration = providerId == null ? null : providers.get(providerId);
        if (registration == null) throw new LookupValidationException("Credential has no enabled data provider");
        return registration;
    }

    @Override
    public void validateLookup(String credentialId, Map<String, Object> claims) {
        if (claims == null) throw new LookupValidationException("Lookup claims are required");
        resolve(credentialId).validator().validate(Collections.unmodifiableMap(new LinkedHashMap<>(claims)));
    }

    @SuppressWarnings("unchecked")
    @Override
    public JSONObject fetchData(Map<String, Object> input) throws DataProviderExchangeException {
        if (!(input.get(CREDENTIAL_CONFIGURATION_ID) instanceof String credentialId)
                || !(input.get(LOOKUP) instanceof Map<?, ?> lookup))
            throw new LookupValidationException("Trusted lookup transaction is required");
        Map<String, Object> claims = new LinkedHashMap<>((Map<String, Object>) lookup);
        DataProviderRegistration registration = resolve(credentialId);
        registration.validator().validate(Collections.unmodifiableMap(claims));
        // Delegates receive only validated lookup values, never wallet or access-token claims.
        return registration.plugin().fetchData(Collections.unmodifiableMap(claims));
    }

    @Override
    public void customizeTemplate(Map<String, Object> input, Map<String, Object> parameters)
            throws DataProviderExchangeException {
        resolve((String) input.get(CREDENTIAL_CONFIGURATION_ID)).templateCustomizer().customize(parameters);
    }
}
