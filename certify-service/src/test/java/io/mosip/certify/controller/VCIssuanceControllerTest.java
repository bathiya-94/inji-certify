package io.mosip.certify.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import foundation.identity.jsonld.JsonLDObject;
import io.mosip.certify.api.spi.AuditPlugin;
import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.constants.VCIErrorConstants;
import io.mosip.certify.core.dto.*;
import io.mosip.certify.core.exception.InvalidRequestException;
import io.mosip.certify.core.spi.CredentialConfigurationService;
import io.mosip.certify.core.spi.VCIssuanceService;
import io.mosip.certify.exception.InvalidNonceException;
import io.mosip.certify.exception.CredentialQrEncodingException;
import io.mosip.certify.services.VCICacheService;
import io.mosip.certify.services.CredentialQrService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@WebMvcTest(value = VCIssuanceController.class)
public class VCIssuanceControllerTest {

    ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AuditPlugin auditWrapper;


    @MockBean
    ParsedAccessToken parsedAccessToken;

    @MockBean
    VCIssuanceService vcIssuanceService;

    @MockBean
    VCICacheService vciCacheService;

    @MockBean
    CredentialQrService credentialQrService;

    @MockBean
    CredentialConfigurationService credentialConfigurationService;

    @Test
    public void getIssuerMetadata_noQueryParams_thenPass() throws Exception {
        CredentialIssuerMetadataVD13DTO credentialIssuerMetadata = new CredentialIssuerMetadataVD13DTO();
        credentialIssuerMetadata.setCredentialIssuer("https://localhost:9090");
        credentialIssuerMetadata.setAuthorizationServers(List.of("https://example.com/auth"));
        credentialIssuerMetadata.setCredentialEndpoint("https://localhost:9090/v1/certify/issuance/credential");
        Map<String, String> display = new HashMap<>();
        display.put("name", "Test Credential Issuer");
        display.put("locale", "en");
        credentialIssuerMetadata.setDisplay(List.of(display));

        CredentialConfigurationSupportedDTO credentialConfigurationSupported = new CredentialConfigurationSupportedDTO();
        credentialConfigurationSupported.setFormat("ldp_vc");
        credentialConfigurationSupported.setScope("test_vc_ldp");
        credentialConfigurationSupported.setCryptographicBindingMethodsSupported(List.of("did:jwk"));
        credentialConfigurationSupported.setCredentialSigningAlgValuesSupported(List.of("Ed25519Signature2020"));
        Map<String, Object> jwtValues = Map.of("proof_signing_alg_values_supported", Arrays.asList("RS256", "ES256"));
        credentialConfigurationSupported.setProofTypesSupported(jwtValues);
        credentialConfigurationSupported.setDisplay(List.of());
        credentialConfigurationSupported.setOrder(Arrays.asList("test1", "test2", "test3", "test4"));
        credentialIssuerMetadata.setCredentialConfigurationSupportedDTO(Map.of("TestCredential_ldp", credentialConfigurationSupported));

        Mockito.when(credentialConfigurationService.fetchCredentialIssuerMetadata(Mockito.anyString())).thenReturn(credentialIssuerMetadata);

        mockMvc.perform(get("/issuance/.well-known/openid-credential-issuer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credential_issuer").exists())
                .andExpect(jsonPath("$.credential_issuer").exists())
                .andExpect(jsonPath("$.credential_configurations_supported").exists())
                .andExpect(header().string("Content-Type", "application/json"));

        Mockito.verify(credentialConfigurationService).fetchCredentialIssuerMetadata("latest");
    }

    @Test
    public void getIssuerMetadata_withValidQueryParam_thenPass() throws Exception {
        CredentialIssuerMetadataVD13DTO credentialIssuerMetadata = new CredentialIssuerMetadataVD13DTO();
        credentialIssuerMetadata.setCredentialIssuer("https://localhost:9090");
        credentialIssuerMetadata.setAuthorizationServers(List.of("https://example.com/auth"));
        credentialIssuerMetadata.setCredentialEndpoint("https://localhost:9090/v1/certify/issuance/credential");
        Map<String, String> display = new HashMap<>();
        display.put("name", "Test Credential Issuer");
        display.put("locale", "en");
        credentialIssuerMetadata.setDisplay(List.of(display));

        CredentialConfigurationSupportedDTO credentialConfigurationSupported = new CredentialConfigurationSupportedDTO();
        credentialConfigurationSupported.setFormat("ldp_vc");
        credentialConfigurationSupported.setScope("test_vc_ldp");
        credentialConfigurationSupported.setCryptographicBindingMethodsSupported(List.of("did:jwk"));
        credentialConfigurationSupported.setCredentialSigningAlgValuesSupported(List.of("Ed25519Signature2020"));
        Map<String, Object> jwtValues = Map.of("proof_signing_alg_values_supported", Arrays.asList("RS256", "ES256"));
        credentialConfigurationSupported.setProofTypesSupported(jwtValues);
        credentialConfigurationSupported.setDisplay(List.of());
        credentialConfigurationSupported.setOrder(Arrays.asList("test1", "test2", "test3", "test4"));
        credentialIssuerMetadata.setCredentialConfigurationSupportedDTO(Map.of("TestCredential_ldp", credentialConfigurationSupported));


        Mockito.when(credentialConfigurationService.fetchCredentialIssuerMetadata("vd13")).thenReturn(credentialIssuerMetadata);

        mockMvc.perform(get("/issuance/.well-known/openid-credential-issuer?version=vd13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credential_issuer").exists())
                .andExpect(jsonPath("$.credential_endpoint").exists())
                .andExpect(jsonPath("$.credential_configurations_supported").exists())
                .andExpect(header().string("Content-Type", "application/json"));

        Mockito.verify(credentialConfigurationService).fetchCredentialIssuerMetadata("vd13");
    }

    @Test
    public void getIssuerMetadata_withInvalidQueryParam_thenFail() throws Exception {
        Exception e = new InvalidRequestException(ErrorConstants.UNSUPPORTED_OPENID_VERSION);
        Mockito.when(credentialConfigurationService.fetchCredentialIssuerMetadata("v123")).thenThrow(e);
        mockMvc.perform(get("/issuance/.well-known/openid-credential-issuer?version=v123"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    public void getVerifiableCredential_withValidDetails_thenPass() throws Exception {
        CredentialDefinition credentialDefinition = new CredentialDefinition();
        credentialDefinition.setType(Arrays.asList("VerifiableCredential", "SampleVerifiableCredential_ldp"));
        credentialDefinition.setContext(Arrays.asList("https://www.w3.org/2018/credentials/v1"));
        CredentialProof credentialProof = new CredentialProof();
        credentialProof.setProof_type("jwt");
        credentialProof.setJwt("dummy_jwt_proof");
        CredentialRequest credentialRequest = new CredentialRequest();
        credentialRequest.setFormat("ldp_vc");
        credentialRequest.setProof(credentialProof);
        credentialRequest.setCredential_definition(credentialDefinition);

        CredentialResponse credentialResponse = new CredentialResponse<JsonLDObject>();
        credentialResponse.setCredential(new JsonLDObject());
        Mockito.when(vcIssuanceService.getCredential(credentialRequest)).thenReturn(credentialResponse);

        mockMvc.perform(post("/issuance/credential")
                        .content(objectMapper.writeValueAsBytes(credentialRequest))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credential").exists());
    }

    @Test
    public void getVerifiableCredential_withQr_preservesEncodedStringOnAllRoutes() throws Exception {
        String qrData = " A B +/%:0123 ";
        Map<String, Object> credential = signedCredential();
        for (String endpoint : List.of("/issuance/credential", "/issuance/vd11/credential", "/issuance/vd12/credential")) {
            Mockito.reset(vcIssuanceService, credentialQrService);
            CredentialRequest request = qrRequest(true);
            CredentialResponse<Object> response = new CredentialResponse<>();
            response.setCredential(credential);
            Mockito.when(vcIssuanceService.getCredential(request)).thenReturn(response);
            Mockito.when(credentialQrService.encode(credential)).thenReturn(new CredentialQr("pixelpass", qrData));

            mockMvc.perform(post(endpoint)
                            .content(objectMapper.writeValueAsBytes(request))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.credential").value(equalTo(credential)))
                    .andExpect(jsonPath("$.credential_qr.encoding").value("pixelpass"))
                    .andExpect(jsonPath("$.credential_qr.data").value(qrData))
                    .andExpect(jsonPath("$.credential_qr_error").doesNotExist())
                    .andExpect(jsonPath("$.credentialQr").doesNotExist())
                    .andExpect(endpoint.equals("/issuance/credential")
                            ? jsonPath("$.format").doesNotExist() : jsonPath("$.format").value("ldp_vc"));

            Mockito.verify(vcIssuanceService, Mockito.times(1)).getCredential(request);
            Mockito.verify(credentialQrService, Mockito.times(1)).encode(credential);
        }
    }

    @Test
    public void getVerifiableCredential_whenQrEncodingFails_returnsCredentialAndSafeError() throws Exception {
        CredentialRequest request = qrRequest(true);
        Map<String, Object> credential = signedCredential();
        CredentialResponse<Object> response = new CredentialResponse<>();
        response.setCredential(credential);
        Mockito.when(vcIssuanceService.getCredential(request)).thenReturn(response);
        Mockito.when(credentialQrService.encode(credential)).thenThrow(
                new CredentialQrEncodingException("Credential QR encoding failed",
                        new IllegalStateException("private credential detail")));

        mockMvc.perform(post("/issuance/credential")
                        .content(objectMapper.writeValueAsBytes(request))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credential").value(equalTo(credential)))
                .andExpect(jsonPath("$.credential_qr").doesNotExist())
                .andExpect(jsonPath("$.credential_qr_error").value("encoding_failed"))
                .andExpect(content().string(not(containsString("private credential detail"))));

        Mockito.verify(vcIssuanceService, Mockito.times(1)).getCredential(request);
        Mockito.verify(credentialQrService, Mockito.times(1)).encode(credential);
    }

    @Test
    public void getVerifiableCredential_withQrOmittedOrFalse_doesNotEncode() throws Exception {
        for (Boolean includeQr : new Boolean[]{null, false}) {
            Mockito.reset(vcIssuanceService, credentialQrService);
            CredentialRequest request = qrRequest(includeQr);
            Map<String, Object> credential = signedCredential();
            CredentialResponse<Object> response = new CredentialResponse<>();
            response.setCredential(credential);
            Mockito.when(vcIssuanceService.getCredential(request)).thenReturn(response);
            ObjectNode body = objectMapper.valueToTree(request);
            if (includeQr == null) {
                body.remove("include_credential_qr");
            }

            mockMvc.perform(post("/issuance/credential")
                            .content(objectMapper.writeValueAsBytes(body))
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.credential").value(equalTo(credential)))
                    .andExpect(jsonPath("$.credential_qr").doesNotExist())
                    .andExpect(jsonPath("$.credential_qr_error").doesNotExist());

            Mockito.verify(vcIssuanceService, Mockito.times(1)).getCredential(request);
            Mockito.verifyNoInteractions(credentialQrService);
        }
    }

    @Test
    public void getVerifiableCredential_withQrAndUnsupportedFormat_rejectsBeforeIssuance() throws Exception {
        CredentialRequest request = qrRequest(true);
        request.setFormat("jwt_vc_json");

        mockMvc.perform(post("/issuance/credential")
                        .content(objectMapper.writeValueAsBytes(request))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(VCIErrorConstants.UNSUPPORTED_CREDENTIAL_FORMAT));

        Mockito.verifyNoInteractions(vcIssuanceService, credentialQrService);
    }

    @Test
    public void getVerifiableCredential_withQrAndDeferredResponse_doesNotEncode() throws Exception {
        CredentialRequest request = qrRequest(true);
        CredentialResponse<Object> response = new CredentialResponse<>();
        response.setAcceptance_token("deferred-token");
        Mockito.when(vcIssuanceService.getCredential(request)).thenReturn(response);

        mockMvc.perform(post("/issuance/credential")
                        .content(objectMapper.writeValueAsBytes(request))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptance_token").value("deferred-token"))
                .andExpect(jsonPath("$.credential").doesNotExist())
                .andExpect(jsonPath("$.credential_qr").doesNotExist())
                .andExpect(jsonPath("$.credential_qr_error").doesNotExist());

        Mockito.verify(vcIssuanceService, Mockito.times(1)).getCredential(request);
        Mockito.verifyNoInteractions(credentialQrService);
    }

    private CredentialRequest qrRequest(Boolean includeQr) {
        CredentialDefinition definition = new CredentialDefinition();
        definition.setType(List.of("VerifiableCredential", "SampleVerifiableCredential_ldp"));
        definition.setContext(List.of("https://www.w3.org/2018/credentials/v1"));
        CredentialProof proof = new CredentialProof();
        proof.setProof_type("jwt");
        proof.setJwt("dummy_jwt_proof");
        CredentialRequest request = new CredentialRequest();
        request.setFormat("ldp_vc");
        request.setCredential_definition(definition);
        request.setProof(proof);
        request.setIncludeCredentialQr(includeQr);
        return request;
    }

    private Map<String, Object> signedCredential() {
        return Map.of("id", "urn:uuid:test-credential",
                "credentialSubject", Map.of("vehicle_no", "TEST-1234"),
                "proof", Map.of("type", "Ed25519Signature2020", "proofValue", "original-proof"));
    }

    @Test
    public void getVerifiableCredential_withInvalidFormat_thenFail() throws Exception {
        CredentialRequest credentialRequest = new CredentialRequest();
        credentialRequest.setFormat(null);
        CredentialProof credentialProof = new CredentialProof();
        credentialProof.setProof_type("jwt");
        credentialRequest.setProof(credentialProof);
        CredentialDefinition credentialDefinition = new CredentialDefinition();
        credentialDefinition.setType(Arrays.asList("VerifiableCredential", "SampleVerifiableCredential_ldp"));
        credentialRequest.setCredential_definition(credentialDefinition);

        mockMvc.perform(post("/issuance/credential")
                        .content(objectMapper.writeValueAsBytes(credentialRequest))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(ErrorConstants.INVALID_VC_FORMAT));

        credentialRequest.setFormat("  ");
        mockMvc.perform(post("/issuance/credential")
                        .content(objectMapper.writeValueAsBytes(credentialRequest))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(ErrorConstants.INVALID_VC_FORMAT));
    }

    @Test
    public void getVerifiableCredential_withInvalidProof_thenFail() throws Exception {
        CredentialRequest credentialRequest = new CredentialRequest();
        credentialRequest.setFormat("jwt_vc_json");
        CredentialDefinition credentialDefinition = new CredentialDefinition();
        credentialDefinition.setType(Arrays.asList("VerifiableCredential", "SampleVerifiableCredential_ldp"));
        credentialRequest.setCredential_definition(credentialDefinition);

        credentialRequest.setProof(null);
        mockMvc.perform(post("/issuance/credential")
                        .content(objectMapper.writeValueAsBytes(credentialRequest))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(VCIErrorConstants.INVALID_PROOF));

        CredentialProof credentialProof = new CredentialProof();
        credentialRequest.setProof(credentialProof);
        mockMvc.perform(post("/issuance/credential")
                        .content(objectMapper.writeValueAsBytes(credentialRequest))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(ErrorConstants.UNSUPPORTED_PROOF_TYPE));


        credentialProof.setProof_type("  ");
        credentialRequest.setProof(credentialProof);
        mockMvc.perform(post("/issuance/credential")
                        .content(objectMapper.writeValueAsBytes(credentialRequest))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(ErrorConstants.UNSUPPORTED_PROOF_TYPE));
    }

    @Test
    public void getVerifiableCredential_withInvalidNonceException_thenFail() throws Exception {
        CredentialDefinition credentialDefinition = new CredentialDefinition();
        credentialDefinition.setType(Arrays.asList("VerifiableCredential", "SampleVerifiableCredential_ldp"));
        credentialDefinition.setContext(Arrays.asList("https://www.w3.org/2018/credentials/v1"));
        CredentialProof credentialProof = new CredentialProof();
        credentialProof.setProof_type("jwt");
        credentialProof.setJwt("dummy_jwt_proof");
        CredentialRequest credentialRequest = new CredentialRequest();
        credentialRequest.setFormat("ldp_vc");
        credentialRequest.setProof(credentialProof);
        credentialRequest.setCredential_definition(credentialDefinition);

        InvalidNonceException exception = new InvalidNonceException("test-new-nonce", 400);
        Mockito.when(vcIssuanceService.getCredential(credentialRequest)).thenThrow(exception);

        mockMvc.perform(post("/issuance/credential")
                        .content(objectMapper.writeValueAsBytes(credentialRequest))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(exception.getErrorCode()))
                .andExpect(jsonPath("$.c_nonce_expires_in").value(exception.getClientNonceExpireSeconds()))
                .andExpect(jsonPath("$.c_nonce").value(exception.getClientNonce()));
    }

    @Test
    public void getVerifiableCredential_vd11() throws Exception {
        CredentialDefinition credentialDefinition = new CredentialDefinition();
        credentialDefinition.setType(Arrays.asList("VerifiableCredential", "SampleVerifiableCredential_ldp"));
        credentialDefinition.setContext(Arrays.asList("https://www.w3.org/2018/credentials/v1"));
        CredentialProof credentialProof = new CredentialProof();
        credentialProof.setProof_type("jwt");
        credentialProof.setJwt("dummy_jwt_proof");
        CredentialRequest credentialRequest = new CredentialRequest();
        credentialRequest.setFormat("ldp_vc");
        credentialRequest.setProof(credentialProof);
        credentialRequest.setCredential_definition(credentialDefinition);

        CredentialResponse credentialResponse = new CredentialResponse<JsonLDObject>();
        credentialResponse.setFormat("ldp_vc");
        credentialResponse.setCredential(new JsonLDObject());
        Mockito.when(vcIssuanceService.getCredential(credentialRequest)).thenReturn(credentialResponse);

        mockMvc.perform(post("/issuance/vd11/credential")
                        .content(objectMapper.writeValueAsBytes(credentialRequest))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").exists())
                .andExpect(jsonPath("$.credential").exists());
    }

    @Test
    public void getVerifiableCredential_vd12() throws Exception {
        CredentialDefinition credentialDefinition = new CredentialDefinition();
        credentialDefinition.setType(Arrays.asList("VerifiableCredential", "SampleVerifiableCredential_ldp"));
        credentialDefinition.setContext(Arrays.asList("https://www.w3.org/2018/credentials/v1"));
        CredentialProof credentialProof = new CredentialProof();
        credentialProof.setProof_type("jwt");
        credentialProof.setJwt("dummy_jwt_proof");
        CredentialRequest credentialRequest = new CredentialRequest();
        credentialRequest.setFormat("ldp_vc");
        credentialRequest.setProof(credentialProof);
        credentialRequest.setCredential_definition(credentialDefinition);

        CredentialResponse credentialResponse = new CredentialResponse<JsonLDObject>();
        credentialResponse.setFormat("ldp_vc");
        credentialResponse.setCredential(new JsonLDObject());
        Mockito.when(vcIssuanceService.getCredential(credentialRequest)).thenReturn(credentialResponse);

        mockMvc.perform(post("/issuance/vd12/credential")
                        .content(objectMapper.writeValueAsBytes(credentialRequest))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.format").exists())
                .andExpect(jsonPath("$.credential").exists());
    }
}
