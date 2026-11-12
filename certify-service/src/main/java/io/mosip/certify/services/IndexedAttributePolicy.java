package io.mosip.certify.services;

import io.mosip.certify.config.CredentialTypeIndexConfig;
import io.mosip.certify.config.IndexedAttributesConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single place that decides which attributes are extracted for a credential type and which of them may be
 * searched, so extraction and search validation cannot disagree.
 */
@Component
public class IndexedAttributePolicy {

    @Autowired
    private IndexedAttributesConfig config;

    /** The credential type string as stored in the ledger: trimmed, sorted and joined with a comma. */
    public static String normalize(String credentialType) {
        if (credentialType == null) {
            return "";
        }
        List<String> types = new ArrayList<>();
        for (String type : credentialType.split(",")) {
            if (!type.isBlank()) {
                types.add(type.trim());
            }
        }
        Collections.sort(types);
        return String.join(",", types);
    }

    /** JSONPath mappings used to extract attributes: the type's own, else the global ones. */
    public Map<String, String> mappingsFor(String credentialType) {
        CredentialTypeIndexConfig scoped = config.getIndexedTypes().get(normalize(credentialType));
        return scoped != null ? scoped.getMappings() : config.getIndexedMappings();
    }

    /** Attributes that may be used as search filters for the type. */
    public Set<String> searchableFor(String credentialType) {
        CredentialTypeIndexConfig scoped = config.getIndexedTypes().get(normalize(credentialType));
        return scoped != null ? new LinkedHashSet<>(scoped.getSearchableFields())
                : new LinkedHashSet<>(config.getIndexedMappings().keySet());
    }

    /**
     * Checks the requested exact-match filters.
     *
     * @return the first problem found, or null when every filter is permitted
     */
    public String firstProblem(String credentialType, Map<String, String> filters) {
        if (filters == null) {
            return null;
        }
        Set<String> searchable = searchableFor(credentialType);
        for (Map.Entry<String, String> filter : new LinkedHashMap<>(filters).entrySet()) {
            if (filter.getKey() == null || filter.getKey().isBlank()) {
                return "A search attribute name must not be blank.";
            }
            if (filter.getValue() == null || filter.getValue().isBlank()) {
                return "The value of search attribute '" + filter.getKey() + "' must not be blank.";
            }
            if (!searchable.contains(filter.getKey())) {
                return "Search attribute '" + filter.getKey() + "' is not searchable for this credential type. Allowed: "
                        + String.join(", ", searchable) + ".";
            }
        }
        return null;
    }

    static List<String> split(String paths) {
        return Arrays.asList(paths.split("\\|"));
    }
}
