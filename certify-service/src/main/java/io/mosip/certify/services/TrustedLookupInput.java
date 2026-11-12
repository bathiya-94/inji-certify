package io.mosip.certify.services;
import java.util.*;
import io.mosip.certify.api.spi.LookupDataProviderPlugin;
import io.mosip.certify.core.dto.PreAuthTransaction;
import io.mosip.certify.core.exception.InvalidRequestException;
import io.mosip.certify.core.constants.VCIErrorConstants;

public final class TrustedLookupInput {
    private TrustedLookupInput() { }
    public static Map<String, Object> fromTransaction(String credentialId, Object cachedTransaction) {
        if (!(cachedTransaction instanceof PreAuthTransaction transaction)
                || !Objects.equals(credentialId, transaction.getCredentialConfigurationId()) || transaction.getClaims() == null)
            throw new InvalidRequestException(VCIErrorConstants.INVALID_CREDENTIAL_REQUEST);
        return Map.of(LookupDataProviderPlugin.CREDENTIAL_CONFIGURATION_ID, credentialId,
                LookupDataProviderPlugin.LOOKUP, new LinkedHashMap<>(transaction.getClaims()));
    }
}
