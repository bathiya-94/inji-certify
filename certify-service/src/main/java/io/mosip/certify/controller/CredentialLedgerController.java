package io.mosip.certify.controller;

import io.mosip.certify.core.dto.CredentialLedgerSearchRequest;
import io.mosip.certify.core.dto.CredentialStatusResponse;
import io.mosip.certify.core.dto.LedgerSearchResult;
import io.mosip.certify.core.spi.CredentialLedgerService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
public class CredentialLedgerController {

    @Autowired
    private CredentialLedgerService credentialLedgerService;

    @PostMapping(value = "/ledger-search", produces = "application/json")
    public ResponseEntity<List<CredentialStatusResponse>> searchCredentials(
            @Valid @RequestBody CredentialLedgerSearchRequest request) {
        return respond(credentialLedgerService.searchCredentialLedger(request));
    }

    @PostMapping(value = "/v2/ledger-search", produces = "application/json")
    public ResponseEntity<List<CredentialStatusResponse>> searchCredentialsV2(
            @Valid @RequestBody CredentialLedgerSearchRequest request) {
        return respond(credentialLedgerService.searchCredentialLedgerV2(request));
    }

    /** 200 with the records, or 204 when there are none; paged searches also say whether more exist. */
    private static ResponseEntity<List<CredentialStatusResponse>> respond(LedgerSearchResult<CredentialStatusResponse> result) {
        HttpHeaders headers = new HttpHeaders();
        if (result.paginated()) {
            headers.add("X-Has-More", String.valueOf(result.hasMore()));
        }
        if (result.records().isEmpty()) {
            return ResponseEntity.noContent().headers(headers).build();
        }
        return ResponseEntity.ok().headers(headers).body(result.records());
    }
}
