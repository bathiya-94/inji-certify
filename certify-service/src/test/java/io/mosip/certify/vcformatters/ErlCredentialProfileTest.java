package io.mosip.certify.vcformatters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks the ERL example credential configuration against its template and the rendered credential, so the
 * Base64 template in the configuration cannot drift from the template file and the licence is always issued
 * with its own id and a status list entry.
 */
class ErlCredentialProfileTest {

    private static final Path EXAMPLES = Path.of("../erl-dataprovider-plugin/examples");
    private static final Path KIT = Path.of("../../dmt-certify-test-kit/preauth/erl");
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String template() throws Exception {
        return Files.readString(EXAMPLES.resolve("erl-template.vm"));
    }

    private static JsonNode configuration(Path directory) throws Exception {
        return JSON.readTree(Files.readString(directory.resolve("erl-credential-configuration.json")));
    }

    @Test
    void embeddedTemplateIsExactlyTheTemplateFile() throws Exception {
        assumeTrue(Files.isDirectory(EXAMPLES), "ERL examples not found from the module directory");
        String embedded = new String(Base64.getDecoder().decode(configuration(EXAMPLES).get("vcTemplate").asText()), StandardCharsets.UTF_8);
        assertEquals(template(), embedded);
    }

    @Test
    void configurationEnablesRevocationAndUsesTheVcdm2Context() throws Exception {
        assumeTrue(Files.isDirectory(EXAMPLES), "ERL examples not found from the module directory");
        JsonNode config = configuration(EXAMPLES);
        assertEquals("revocation", config.get("credentialStatusPurposes").get(0).asText());
        assertEquals(1, config.get("credentialStatusPurposes").size());
        assertEquals("https://www.w3.org/ns/credentials/v2", config.get("contextURLs").get(0).asText());
    }

    @Test
    void labCopyMatchesTheExamples() throws Exception {
        assumeTrue(Files.isDirectory(EXAMPLES) && Files.isDirectory(KIT), "DMT test kit is not checked out next to this repository");
        assertEquals(template(), Files.readString(KIT.resolve("erl-template.vm")));
        JsonNode example = configuration(EXAMPLES);
        JsonNode lab = configuration(KIT);
        assertEquals(example.get("vcTemplate"), lab.get("vcTemplate"));
        assertEquals(example.get("credentialStatusPurposes"), lab.get("credentialStatusPurposes"));
    }

    @Test
    void renderedCredentialCarriesItsIdStatusEntryAndValidityDates() throws Exception {
        assumeTrue(Files.isDirectory(EXAMPLES), "ERL examples not found from the module directory");
        JSONObject status = new JSONObject(Map.of(
                "id", "https://c.example/v1/certify/credentials/status-list/L1#9",
                "type", "BitstringStatusListEntry",
                "statusPurpose", "revocation",
                "statusListIndex", "9",
                "statusListCredential", "https://c.example/v1/certify/credentials/status-list/L1"));
        JSONObject subject = new JSONObject(Map.of("id", "did:jwk:holder", "licence_id", "000123", "vehicle_no", "WP-ABC-1234"));
        VelocityContext context = new VelocityContext();
        context.put("credentialId", "urn:uuid:5f1d1c2e-0000-4000-8000-000000000001");
        context.put("_issuer", "did:web:issuer.example");
        context.put("validFrom", "2026-11-09T10:00:00Z");
        context.put("validUntil", "2027-11-09T18:30:00Z");
        context.put("erlCredentialStatus", status.toString());
        context.put("erlCredentialSubject", subject.toString());

        StringWriter out = new StringWriter();
        new VelocityEngine().evaluate(context, out, "erl", template());
        JsonNode vc = JSON.readTree(out.toString());

        assertEquals("urn:uuid:5f1d1c2e-0000-4000-8000-000000000001", vc.get("id").asText());
        assertEquals("did:web:issuer.example", vc.get("issuer").asText());
        assertEquals("2026-11-09T10:00:00Z", vc.get("validFrom").asText());
        assertEquals("2027-11-09T18:30:00Z", vc.get("validUntil").asText());
        assertFalse(vc.has("issuanceDate"));
        assertFalse(vc.has("expirationDate"));
        assertEquals("BitstringStatusListEntry", vc.get("credentialStatus").get("type").asText());
        assertEquals("revocation", vc.get("credentialStatus").get("statusPurpose").asText());
        assertEquals("9", vc.get("credentialStatus").get("statusListIndex").asText());
        assertEquals("000123", vc.get("credentialSubject").get("licence_id").asText(), "leading zeros must survive");
        assertEquals(List.of("VerifiableCredential", "ERLCredential"),
                List.of(vc.get("type").get(0).asText(), vc.get("type").get(1).asText()));
    }
}
