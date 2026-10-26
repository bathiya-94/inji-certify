package io.mosip.certify.api.spi;
import java.util.Map;
import io.mosip.certify.api.exception.DataProviderExchangeException;
@FunctionalInterface
public interface CredentialTemplateCustomizer {
    void customize(Map<String, Object> templateParameters) throws DataProviderExchangeException;
}
