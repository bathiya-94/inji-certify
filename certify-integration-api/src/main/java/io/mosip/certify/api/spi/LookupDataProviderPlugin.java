package io.mosip.certify.api.spi;

import java.util.Map;
import io.mosip.certify.api.exception.DataProviderExchangeException;

/** Server-side lookup contract. Public credential requests never supply these reserved keys. */
public interface LookupDataProviderPlugin extends DataProviderPlugin {
    String CREDENTIAL_CONFIGURATION_ID = "_certifyCredentialConfigurationId";
    String LOOKUP = "_certifyLookup";

    void validateLookup(String credentialConfigurationId, Map<String, Object> claims);

    /** Runs after generic template defaults have been applied. */
    default void customizeTemplate(Map<String, Object> trustedInput, Map<String, Object> templateParameters)
            throws DataProviderExchangeException { }
}
