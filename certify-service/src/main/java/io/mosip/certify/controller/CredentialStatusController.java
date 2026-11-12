/*More actions
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.certify.controller;

import io.mosip.certify.config.StatusListPublicationProperties;
import io.mosip.certify.core.dto.CredentialStatusResponse;
import io.mosip.certify.core.dto.UpdateCredentialStatusRequest;
import io.mosip.certify.core.dto.UpdateCredentialStatusRequestV2;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.core.spi.CredentialStatusService;
import io.mosip.certify.services.StatusListCredentialService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/credentials")
public class CredentialStatusController {

    @Autowired
    private StatusListCredentialService statusListCredentialService;

    @Autowired
    private CredentialStatusService credentialStatusService;

    @Autowired
    private StatusListPublicationProperties publicationProperties;

    /**
     * Get Status List Credential by ID with optional fragment support
     * Handles URLs like: /{id} or /{id}#{fragment}
     *
     * @param id The status list credential ID
    //     * @param fragment Optional fragment identifier (for specific index references)
     * @return Status List VC JSON document
     * @throws CertifyException
     */
    @GetMapping(value = "/status-list/{id}", produces = "application/json")
    public ResponseEntity<String> getStatusListById(@PathVariable("id") String id,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) throws CertifyException {

        log.debug("Retrieving status list credential with ID: {}", id);
        StatusListCredentialService.PublishedStatusList list = statusListCredentialService.getPublishedStatusList(id);
        long seconds = publicationProperties.cacheSeconds();
        ResponseEntity.BodyBuilder response = (matches(ifNoneMatch, list.etag()) ? ResponseEntity.status(HttpStatus.NOT_MODIFIED) : ResponseEntity.ok())
                .eTag(list.etag())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=" + seconds + ", s-maxage=" + seconds + ", must-revalidate");
        return matches(ifNoneMatch, list.etag()) ? response.build() : response.contentType(MediaType.APPLICATION_JSON).body(list.body());
    }

    /** Whether any validator in an If-None-Match header names the current version ({@code *} matches anything). */
    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        for (String candidate : ifNoneMatch.split(",")) {
            String tag = candidate.trim();
            if (tag.equals("*") || (tag.startsWith("W/") ? tag.substring(2) : tag).equals(etag)) {
                return true;
            }
        }
        return false;
    }

    @PostMapping(value = "/status", produces = "application/json")
    public ResponseEntity<CredentialStatusResponse> updateCredential(
            @Valid @RequestBody UpdateCredentialStatusRequest updateCredentialStatusRequest) {
        CredentialStatusResponse result = credentialStatusService.updateCredentialStatus(updateCredentialStatusRequest);
        if (result == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping(value = "/v2/status", produces = "application/json")
    public ResponseEntity<CredentialStatusResponse> updateCredentialV2(
            @Valid @RequestBody UpdateCredentialStatusRequestV2 updateCredentialStatusRequestV2) {
        CredentialStatusResponse result = credentialStatusService.updateCredentialStatusV2(updateCredentialStatusRequestV2);
        if (result == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result);
    }
}