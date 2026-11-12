/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.certify.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import co.nstant.in.cbor.CborDecoder;
import io.mosip.pixelpass.cbor.Utils;
import io.mosip.certify.core.dto.CredentialQr;
import io.mosip.certify.exception.CredentialQrEncodingException;
import io.mosip.pixelpass.PixelPass;
import nl.minvws.encoding.Base45;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.util.zip.InflaterInputStream;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CredentialQrServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void completeCredentialSurvivesEncodingWithoutMutation() throws Exception {
        JsonNode vc = mapper.readTree("""
                {"@context":["https://www.w3.org/2018/credentials/v1"],
                 "credentialSubject":{"owner":"බණ්ඩාර", "fee":125.5,"optional":null,
                 "address":"Line 1\\nLine 2 \\\"quoted\\\" \\\\ path"},
                 "proof":{"type":"Ed25519Signature2020","proofValue":"zSignature"}}
                """);
        JsonNode original = vc.deepCopy();
        CredentialQr qr = new CredentialQrService(mapper, new PixelPass()).encode(vc);
        assertEquals("pixelpass", qr.getEncoding());
        byte[] compressed = Base45.getDecoder().decode(qr.getData());
        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            JsonNode decoded = mapper.readTree(new Utils().toJson(new CborDecoder(input).decode().getFirst()).toString());
            assertEquals(original, decoded);
        }
        assertEquals(original, vc);
    }

    @Test
    public void serializationFailureIsWrappedWithItsCause() {
        Object cyclic = new Object() { public Object self = this; };
        PixelPass pixelPass = mock(PixelPass.class);
        CredentialQrEncodingException exception = assertThrows(CredentialQrEncodingException.class,
                () -> new CredentialQrService(mapper, pixelPass).encode(cyclic));
        assertEquals("Credential QR encoding failed", exception.getMessage());
        assertTrue(exception.getCause() instanceof JsonProcessingException);
        verifyNoInteractions(pixelPass);
    }

    @Test
    public void encoderFailureIsWrappedWithItsCause() {
        PixelPass pixelPass = mock(PixelPass.class);
        IllegalStateException cause = new IllegalStateException("private credential detail");
        when(pixelPass.generateQRData(anyString(), eq(""))).thenThrow(cause);
        CredentialQrEncodingException exception = assertThrows(CredentialQrEncodingException.class,
                () -> new CredentialQrService(mapper, pixelPass).encode(mapper.createObjectNode()));
        assertEquals("Credential QR encoding failed", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    public void nonObjectCredentialIsRejectedBeforeEncoding() {
        PixelPass pixelPass = mock(PixelPass.class);
        CredentialQrService service = new CredentialQrService(mapper, pixelPass);
        for (Object credential : new Object[]{"not a credential object", null, mapper.createArrayNode()}) {
            CredentialQrEncodingException exception = assertThrows(CredentialQrEncodingException.class,
                    () -> service.encode(credential));
            assertEquals("Expected an ldp_vc JSON object", exception.getMessage());
            assertNull(exception.getCause());
        }
        verifyNoInteractions(pixelPass);
    }

    @Test
    public void nullOrEmptyEncodingResultIsRejected() {
        PixelPass pixelPass = mock(PixelPass.class);
        CredentialQrService service = new CredentialQrService(mapper, pixelPass);
        for (String result : new String[]{null, ""}) {
            when(pixelPass.generateQRData(anyString(), eq(""))).thenReturn(result);
            CredentialQrEncodingException exception = assertThrows(CredentialQrEncodingException.class,
                    () -> service.encode(mapper.createObjectNode()));
            assertEquals("Empty PixelPass result", exception.getMessage());
            assertNull(exception.getCause());
        }
    }
}
