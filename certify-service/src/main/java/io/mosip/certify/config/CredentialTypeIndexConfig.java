package io.mosip.certify.config;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Indexed attributes of one credential type: how to extract them and which ones may be searched. */
@Getter
@Setter
public class CredentialTypeIndexConfig {
    /** Attribute name to JSONPath (alternatives separated by '|'), extracted from trusted provider data. */
    private Map<String, String> mappings = new LinkedHashMap<>();
    /** Attributes that may be used as exact-match search filters. Others are stored as metadata only. */
    private List<String> searchableFields = new ArrayList<>();
}
