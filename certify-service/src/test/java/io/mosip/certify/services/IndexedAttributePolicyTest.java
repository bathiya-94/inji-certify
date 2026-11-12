package io.mosip.certify.services;

import io.mosip.certify.config.CredentialTypeIndexConfig;
import io.mosip.certify.config.IndexedAttributesConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IndexedAttributePolicyTest {

    private IndexedAttributePolicy policy;

    @BeforeEach
    void setUp() {
        IndexedAttributesConfig config = new IndexedAttributesConfig();
        config.getIndexedMappings().put("state", "$.state");
        config.getIndexedMappings().put("district", "$.district");
        CredentialTypeIndexConfig erl = new CredentialTypeIndexConfig();
        erl.getMappings().putAll(Map.of("document_id", "$.license_no",
                "vehicle_no", "$.vehicle_no", "issued_date", "$.issued_date"));
        erl.setSearchableFields(List.of("document_id", "vehicle_no"));
        config.getIndexedTypes().put("ERLCredential,VerifiableCredential", erl);
        policy = new IndexedAttributePolicy();
        ReflectionTestUtils.setField(policy, "config", config);
    }

    @Test
    void normalisesTypesLikeTheLedgerStoresThem() {
        assertEquals("ERLCredential,VerifiableCredential", IndexedAttributePolicy.normalize("VerifiableCredential, ERLCredential"));
        assertEquals("A,B", IndexedAttributePolicy.normalize(" B,,A "));
        assertEquals("", IndexedAttributePolicy.normalize(null));
    }

    @Test
    void scopedConfigurationTakesPrecedence() {
        assertEquals("$.license_no", policy.mappingsFor("VerifiableCredential,ERLCredential").get("document_id"));
        assertFalse(policy.mappingsFor("ERLCredential,VerifiableCredential").containsKey("state"));
    }

    @Test
    void typesWithoutScopedConfigurationFallBackToGlobalMappings() {
        assertEquals(Set.of("state", "district"), policy.searchableFor("Farmer,VerifiableCredential"));
        assertEquals("$.state", policy.mappingsFor("Farmer,VerifiableCredential").get("state"));
    }

    @Test
    void storedMetadataIsNotSearchable() {
        assertEquals(Set.of("document_id", "vehicle_no"), policy.searchableFor("ERLCredential,VerifiableCredential"));
        assertNotNull(policy.firstProblem("ERLCredential,VerifiableCredential", Map.of("issued_date", "2026-10-16T14:30:00")));
    }

    @Test
    void acceptsPermittedFilters_andRejectsBlankOrUnsupportedOnes() {
        String type = "ERLCredential,VerifiableCredential";
        assertNull(policy.firstProblem(type, Map.of("document_id", "42", "vehicle_no", "WP-1")));
        assertNull(policy.firstProblem(type, null));
        assertNotNull(policy.firstProblem(type, Map.of("owner_name", "x")));
        assertNotNull(policy.firstProblem(type, Map.of("document_id", " ")));
        assertNotNull(policy.firstProblem(type, Map.of(" ", "x")));
        assertTrue(policy.firstProblem(type, Map.of("state", "x")).contains("document_id"), "message lists what is allowed");
    }
}
