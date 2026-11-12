package io.mosip.certify.utils;

import io.mosip.certify.config.CredentialTypeIndexConfig;
import io.mosip.certify.config.IndexedAttributesConfig;
import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.services.IndexedAttributePolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LedgerUtilsTest {

    private LedgerUtils ledgerUtils;
    private IndexedAttributesConfig config;

    @BeforeEach
    void setUp() {
        config = new IndexedAttributesConfig();
        config.getIndexedMappings().put("state", "$.state");
        CredentialTypeIndexConfig erl = new CredentialTypeIndexConfig();
        erl.getMappings().putAll(Map.of("document_id", "$.license_no",
                "vehicle_no", "$.vehicle_no", "issued_date", "$.issued_date"));
        erl.setSearchableFields(List.of("document_id", "vehicle_no"));
        config.getIndexedTypes().put("ERLCredential,VerifiableCredential", erl);
        CredentialTypeIndexConfig driving = new CredentialTypeIndexConfig();
        driving.getMappings().put("document_id", "$.drivers_license_id");
        driving.setSearchableFields(List.of("document_id"));
        config.getIndexedTypes().put("DrivingLicence,VerifiableCredential", driving);
        IndexedAttributePolicy policy = new IndexedAttributePolicy();
        ReflectionTestUtils.setField(policy, "config", config);
        ledgerUtils = new LedgerUtils();
        ReflectionTestUtils.setField(ledgerUtils, "indexedAttributePolicy", policy);
    }

    private static JSONObject erl(Object licenceId) {
        return new JSONObject().put("type", new JSONArray(List.of("VerifiableCredential", "ERLCredential")))
                .put("license_no", licenceId).put("licence_id", "99").put("vehicle_no", "WP-ABC-1234").put("issued_date", "2026-10-16T14:30:00")
                .put("owner_name", "not indexed");
    }

    @Test
    void erlMapsTheLicenceNumberIntoDocumentIdAndKeepsTheNativeFields() {
        Map<String, Object> attributes = ledgerUtils.extractIndexedAttributes(erl("18446744073709551614"));

        assertEquals("18446744073709551614", attributes.get("document_id"));
        assertFalse(attributes.containsKey("licence_id"), "the DMT row id is not indexed");
        assertEquals("WP-ABC-1234", attributes.get("vehicle_no"));
        assertEquals("2026-10-16T14:30:00", attributes.get("issued_date"));
        assertFalse(attributes.containsKey("owner_name"));
        assertFalse(attributes.containsKey("state"), "scoped configuration replaces the global mappings");
    }

    @Test
    void documentIdKeepsLeadingZeros() {
        assertEquals("000123", ledgerUtils.extractIndexedAttributes(erl("000123")).get("document_id"));
    }

    @Test
    void numericSourceIdBecomesAString() {
        assertEquals("123", ledgerUtils.extractIndexedAttributes(erl(123)).get("document_id"));
    }

    @Test
    void aSecondDocumentTypeUsesItsOwnSourceField() {
        JSONObject licence = new JSONObject().put("type", new JSONArray(List.of("VerifiableCredential", "DrivingLicence")))
                .put("drivers_license_id", "DL-0099").put("licence_id", "must-not-be-read");

        Map<String, Object> attributes = ledgerUtils.extractIndexedAttributes(licence);

        assertEquals(Map.of("document_id", "DL-0099"), attributes);
    }

    @Test
    void typesWithoutScopedConfigurationUseTheGlobalMappings() {
        JSONObject farmer = new JSONObject().put("type", new JSONArray(List.of("VerifiableCredential", "Farmer")))
                .put("state", "Kerala").put("licence_id", "ignored");

        assertEquals(Map.of("state", "Kerala"), ledgerUtils.extractIndexedAttributes(farmer));
    }

    @Test
    void aDeclaredDocumentIdThatCannotBeResolvedIsInvalidProviderData() {
        JSONObject missing = erl("x");
        missing.remove("license_no");
        for (JSONObject bad : List.of(missing, erl(" "), erl(JSONObject.NULL), erl(new JSONObject().put("a", 1)), erl(new JSONArray(List.of("a", "b"))))) {
            CertifyException error = assertThrows(CertifyException.class, () -> ledgerUtils.extractIndexedAttributes(bad));
            assertEquals(ErrorConstants.INVALID_PROVIDER_DATA, error.getErrorCode());
        }
    }

    @Test
    void noMappingsMeansNoAttributes() {
        config.getIndexedMappings().clear();
        JSONObject plain = new JSONObject().put("type", new JSONArray(List.of("VerifiableCredential", "Plain")));
        assertTrue(ledgerUtils.extractIndexedAttributes(plain).isEmpty());
    }
}
