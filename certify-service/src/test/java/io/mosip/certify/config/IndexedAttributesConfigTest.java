package io.mosip.certify.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IndexedAttributesConfigTest {

    private static IndexedAttributesConfig bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bind("mosip.certify", IndexedAttributesConfig.class).get();
    }

    @Test
    void bindsATypeKeyWithACommaAndFieldNamesWithUnderscores() {
        IndexedAttributesConfig config = bind(Map.of(
                "mosip.certify.indexed-types[ERLCredential,VerifiableCredential].mappings[document_id]", "$.license_no",
                "mosip.certify.indexed-types[ERLCredential,VerifiableCredential].mappings[vehicle_no]", "$.vehicle_no",
                "mosip.certify.indexed-types[ERLCredential,VerifiableCredential].mappings[issued_date]", "$.issued_date",
                "mosip.certify.indexed-types[ERLCredential,VerifiableCredential].searchable-fields", "document_id,vehicle_no",
                "mosip.certify.indexed-mappings.state", "$.state"));

        CredentialTypeIndexConfig erl = config.getIndexedTypes().get("ERLCredential,VerifiableCredential");
        assertNotNull(erl, "the comma inside the bracket must stay part of the key");
        assertEquals("$.license_no", erl.getMappings().get("document_id"));
        assertEquals("$.issued_date", erl.getMappings().get("issued_date"));
        assertEquals(List.of("document_id", "vehicle_no"), erl.getSearchableFields());
        assertEquals("$.state", config.getIndexedMappings().get("state"));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void secondDocumentTypeBindsItsOwnSourcePath() {
        IndexedAttributesConfig config = bind(Map.of(
                "mosip.certify.indexed-types[DrivingLicence,VerifiableCredential].mappings[document_id]", "$.drivers_license_id",
                "mosip.certify.indexed-types[DrivingLicence,VerifiableCredential].searchable-fields", "document_id"));
        assertEquals("$.drivers_license_id", config.getIndexedTypes().get("DrivingLicence,VerifiableCredential").getMappings().get("document_id"));
        assertDoesNotThrow(config::validate);
    }

    @Test
    void rejectsATypeKeyThatIsNotNormalised() {
        IndexedAttributesConfig config = bind(Map.of(
                "mosip.certify.indexed-types[VerifiableCredential,ERLCredential].mappings[document_id]", "$.license_no"));
        assertThrows(IllegalStateException.class, config::validate);
    }

    @Test
    void rejectsASearchableFieldWithoutAMapping() {
        IndexedAttributesConfig config = bind(Map.of(
                "mosip.certify.indexed-types[ERLCredential,VerifiableCredential].mappings[document_id]", "$.license_no",
                "mosip.certify.indexed-types[ERLCredential,VerifiableCredential].searchable-fields", "document_id,vehicle_no"));
        assertThrows(IllegalStateException.class, config::validate);
    }

    @Test
    void rejectsABlankJsonPath() {
        IndexedAttributesConfig config = new IndexedAttributesConfig();
        CredentialTypeIndexConfig type = new CredentialTypeIndexConfig();
        type.getMappings().put("document_id", " ");
        config.getIndexedTypes().put("ERLCredential,VerifiableCredential", type);
        assertThrows(IllegalStateException.class, config::validate);
    }
}
