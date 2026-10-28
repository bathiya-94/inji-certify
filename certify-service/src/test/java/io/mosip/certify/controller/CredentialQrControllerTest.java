/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.certify.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.certify.core.dto.*;
import io.mosip.certify.core.exception.InvalidRequestException;
import io.mosip.certify.core.spi.VCIssuanceService;
import io.mosip.certify.services.CredentialQrService;
import org.junit.Before;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CredentialQrControllerTest {
    private final VCIssuanceController controller = new VCIssuanceController();
    private final VCIssuanceService issuance = mock(VCIssuanceService.class);
    private final CredentialQrService qrService = mock(CredentialQrService.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Before
    public void setup() {
        ReflectionTestUtils.setField(controller, "vcIssuanceService", issuance);
        ReflectionTestUtils.setField(controller, "credentialQrService", qrService);
    }

    private CredentialRequest request(Boolean include) {
        CredentialRequest request = new CredentialRequest();
        request.setFormat("ldp_vc");
        request.setIncludeCredentialQr(include);
        return request;
    }

    private CredentialResponse<Object> response() {
        CredentialResponse<Object> response = new CredentialResponse<>();
        response.setCredential(mapper.createObjectNode().put("proof", "original-proof"));
        return response;
    }

    @Test
    public void allRoutesUseOneEncodingOperationAndPreserveDraftFormat() throws Exception {
        for (String route : new String[]{"current", "vd11", "vd12"}) {
            reset(issuance, qrService);
            CredentialRequest request = request(true);
            CredentialResponse<Object> response = response();
            when(issuance.getCredential(request)).thenReturn(response);
            when(qrService.encode(response.getCredential())).thenReturn(new CredentialQr("pixelpass", " A B "));
            CredentialResponse actual = switch (route) {
                case "vd11" -> controller.getCredentialV11Draft(request);
                case "vd12" -> controller.getCredentialV12Draft(request);
                default -> controller.getCredential(request);
            };
            JsonNode json = mapper.valueToTree(actual);
            assertEquals(" A B ", json.at("/credential_qr/data").asText());
            assertEquals("pixelpass", json.at("/credential_qr/encoding").asText());
            assertFalse(json.has("credential_qr_error"));
            assertFalse(json.has("credentialQr"));
            assertSame(response.getCredential(), actual.getCredential());
            assertEquals(route.equals("current") ? null : "ldp_vc", actual.getFormat());
            verify(issuance, times(1)).getCredential(request);
            verify(qrService, times(1)).encode(response.getCredential());
        }
    }

    @Test
    public void falseAndAbsentPreserveExistingResponse() throws Exception {
        for (Boolean flag : new Boolean[]{null, false}) {
            CredentialRequest request = request(flag);
            when(issuance.getCredential(request)).thenReturn(response());
            JsonNode actual = mapper.valueToTree(controller.getCredential(request));
            assertFalse(actual.has("credential_qr"));
            assertFalse(actual.has("credential_qr_error"));
            assertEquals(1, actual.size());
        }
        verifyNoInteractions(qrService);
    }

    @Test
    public void unsupportedFormatIsRejectedBeforeIssuance() {
        CredentialRequest request = request(true);
        request.setFormat("mso_mdoc");
        assertThrows(InvalidRequestException.class, () -> controller.getCredential(request));
        verifyNoInteractions(issuance, qrService);
    }

    @Test
    public void qrFailurePreservesCredentialAndDoesNotRepeatIssuance() throws Exception {
        CredentialRequest request = request(true);
        CredentialResponse<Object> response = response();
        when(issuance.getCredential(request)).thenReturn(response);
        when(qrService.encode(any())).thenThrow(new IllegalStateException("private detail"));
        JsonNode actual = mapper.valueToTree(controller.getCredential(request));
        assertEquals(response.getCredential(), actual.get("credential"));
        assertEquals("encoding_failed", actual.get("credential_qr_error").asText());
        assertFalse(actual.has("credential_qr"));
        assertFalse(actual.toString().contains("private detail"));
        verify(issuance, times(1)).getCredential(request);
    }

    @Test
    public void deferredResponseIsNotEncoded() throws Exception {
        CredentialRequest request = request(true);
        CredentialResponse response = new CredentialResponse();
        response.setAcceptance_token("deferred-token");
        when(issuance.getCredential(request)).thenReturn(response);
        JsonNode actual = mapper.valueToTree(controller.getCredential(request));
        assertEquals("deferred-token", actual.get("acceptance_token").asText());
        assertFalse(actual.has("credential_qr"));
        assertFalse(actual.has("credential_qr_error"));
        verifyNoInteractions(qrService);
    }

    @Test
    public void issuanceFailureIsPropagatedWithoutEncoding() throws Exception {
        CredentialRequest request = request(true);
        when(issuance.getCredential(request)).thenThrow(new InvalidRequestException("invalid_proof"));
        assertThrows(InvalidRequestException.class, () -> controller.getCredential(request));
        verifyNoInteractions(qrService);
    }

    @Test
    public void requestUsesSnakeCaseProperty() throws Exception {
        CredentialRequest request = mapper.readValue("{\"format\":\"ldp_vc\",\"include_credential_qr\":true}", CredentialRequest.class);
        assertTrue(request.getIncludeCredentialQr());
        JsonNode json = mapper.valueToTree(request);
        assertTrue(json.get("include_credential_qr").asBoolean());
        assertFalse(json.has("includeCredentialQr"));
    }
}
