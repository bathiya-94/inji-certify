/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.certify.controller;

import io.mosip.certify.core.dto.*;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.core.exception.InvalidRequestException;
import io.mosip.certify.core.constants.VCIErrorConstants;
import io.mosip.certify.core.spi.CredentialConfigurationService;
import io.mosip.certify.core.spi.VCIssuanceService;
import io.mosip.certify.exception.InvalidNonceException;
import io.mosip.certify.services.CredentialQrService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/issuance")
public class VCIssuanceController {

    @Autowired
    private VCIssuanceService vcIssuanceService;

    @Autowired
    private CredentialQrService credentialQrService;

    @Autowired
    private CredentialConfigurationService credentialConfigurationService;

    @Autowired
    MessageSource messageSource;

    /**
     * 1. The credential Endpoint MUST accept Access Tokens
     * @param credentialRequest VC credential request
     * @return Credential Response w.r.t requested format
     * @throws CertifyException
     */
    @PostMapping(value = "/credential",produces = "application/json")
    public CredentialResponse getCredential(@Valid @RequestBody CredentialRequest credentialRequest) throws CertifyException {
        log.info("Get credential request received for format: {}", credentialRequest.getFormat());
        return issueCredential(credentialRequest);
    }

    /**
     * 1. The credential Endpoint MUST accept Access Tokens
     * @param credentialRequest VC credential request
     * @return Credential Response w.r.t requested format
     * @throws CertifyException
     */
    @PostMapping(value = "/vd12/credential",produces = "application/json")
    public CredentialResponse getCredentialV12Draft(@Valid @RequestBody CredentialRequest credentialRequest) throws CertifyException {
        CredentialResponse credentialResponse = issueCredential(credentialRequest);
        credentialResponse.setFormat(credentialRequest.getFormat());
        return credentialResponse;
    }


    /**
     * 1. The credential Endpoint MUST accept Access Tokens
     * @param credentialRequest VC credential request
     * @return Credential Response w.r.t requested format
     * @throws CertifyException
     */
    @PostMapping(value = "/vd11/credential",produces = "application/json")
    public CredentialResponse getCredentialV11Draft(@Valid @RequestBody CredentialRequest credentialRequest) throws CertifyException {
        CredentialResponse credentialResponse = issueCredential(credentialRequest);
        credentialResponse.setFormat(credentialRequest.getFormat());
        return credentialResponse;
    }
    private CredentialResponse issueCredential(CredentialRequest request) throws CertifyException {
        boolean includeQr = Boolean.TRUE.equals(request.getIncludeCredentialQr());
        if (includeQr && !"ldp_vc".equals(request.getFormat())) {
            throw new InvalidRequestException(VCIErrorConstants.UNSUPPORTED_CREDENTIAL_FORMAT);
        }
        CredentialResponse response = vcIssuanceService.getCredential(request);
        if (includeQr && response.getCredential() != null) {
            try {
                response.setCredentialQr(credentialQrService.encode(response.getCredential()));
            } catch (Exception exception) {
                // Issuance has succeeded. Preserve the VC and never repeat issuance for a QR failure.
                // Do not log exception messages: serializers can include credential values.
                log.warn("Credential issued successfully; QR encoding failed");
                response.setCredentialQrError("encoding_failed");
            }
        }
        return response;
    }

    /**
     * Open endpoint to provide VC issuer's metadata
     * @return
     */
    @Deprecated
    @GetMapping(value = "/.well-known/openid-credential-issuer",produces = "application/json")
    public CredentialIssuerMetadataDTO getMetadata(
            @RequestParam(name = "version", required = false, defaultValue = "latest") String version) {
        return credentialConfigurationService.fetchCredentialIssuerMetadata(version);
    }

    @Deprecated
    @GetMapping(value = "/.well-known/did.json", produces = "application/json")
    public Map<String, Object> getDIDDocument() {
       return vcIssuanceService.getDIDDocument();
    }


    @ResponseBody
    @ExceptionHandler(InvalidNonceException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public VCError invalidNonceExceptionHandler(InvalidNonceException ex) {
        VCError vcError = new VCError();
        vcError.setError(ex.getErrorCode());
        vcError.setError_description(messageSource.getMessage(ex.getErrorCode(), null, ex.getErrorCode(), Locale.getDefault()));
        vcError.setC_nonce(ex.getClientNonce());
        vcError.setC_nonce_expires_in(ex.getClientNonceExpireSeconds());
        return vcError;
    }
}
