package io.mosip.certify.api.spi;
import java.util.Map;
@FunctionalInterface
public interface LookupValidator {
    void validate(Map<String, Object> claims);
}
