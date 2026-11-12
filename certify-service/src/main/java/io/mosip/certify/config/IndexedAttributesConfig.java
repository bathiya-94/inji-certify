package io.mosip.certify.config;

import io.mosip.certify.services.IndexedAttributePolicy;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "mosip.certify")
@Getter
@Setter
public class IndexedAttributesConfig {
    /**
     * Holds the mappings from a desired attribute name (key) to the
     * JSONPath expression (value) used to extract it from the source data.
     * Applies to every credential type that has no scoped configuration in {@link #indexedTypes}.
     */
    private Map<String, String> indexedMappings = new HashMap<>();

    /**
     * Per-credential-type configuration, keyed by the normalised type string stored in the ledger
     * (types sorted alphabetically and joined with a comma, for example
     * {@code ERLCredential,VerifiableCredential}). A scoped entry takes precedence over the global mappings.
     */
    private Map<String, CredentialTypeIndexConfig> indexedTypes = new LinkedHashMap<>();

    @PostConstruct
    void validate() {
        indexedTypes.forEach((type, config) -> {
            if (type == null || type.isBlank() || !type.equals(IndexedAttributePolicy.normalize(type))) {
                throw new IllegalStateException("mosip.certify.indexed-types key '" + type
                        + "' must list the credential types sorted alphabetically and joined by ',' without spaces");
            }
            config.getMappings().forEach((attribute, path) -> {
                if (attribute == null || attribute.isBlank() || path == null || path.isBlank()) {
                    throw new IllegalStateException("mosip.certify.indexed-types[" + type + "] has a blank attribute name or JSONPath");
                }
            });
            for (String field : config.getSearchableFields()) {
                if (!config.getMappings().containsKey(field)) {
                    throw new IllegalStateException("mosip.certify.indexed-types[" + type + "] lists searchable field '"
                            + field + "' that has no mapping");
                }
            }
        });
    }
}
