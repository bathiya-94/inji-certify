/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.certify.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.mosip.certify.core.dto.CredentialQr;
import io.mosip.certify.exception.CredentialQrEncodingException;
import io.mosip.pixelpass.PixelPass;
import org.springframework.stereotype.Service;

/** Encodes the final credential without changing its claims or proof. */
@Service
public class CredentialQrService {
    private final ObjectMapper objectMapper;
    private final PixelPass pixelPass;

    public CredentialQrService(ObjectMapper objectMapper, PixelPass pixelPass) {
        this.objectMapper = objectMapper;
        this.pixelPass = pixelPass;
    }

    public CredentialQr encode(Object credential) {
        try {
            String json = objectMapper.writer().without(SerializationFeature.INDENT_OUTPUT)
                    .writeValueAsString(credential);
            if (!objectMapper.readTree(json).isObject()) {
                throw new CredentialQrEncodingException("Expected an ldp_vc JSON object");
            }
            String data = pixelPass.generateQRData(json, "");
            if (data == null || data.isEmpty()) {
                throw new CredentialQrEncodingException("Empty PixelPass result");
            }
            return new CredentialQr("pixelpass", data);
        } catch (CredentialQrEncodingException exception) {
            throw exception;
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new CredentialQrEncodingException("Credential QR encoding failed", exception);
        }
    }
}
