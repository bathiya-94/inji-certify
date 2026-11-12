package io.mosip.certify.controller;

import io.mosip.certify.advice.ExceptionHandlerAdvice;
import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.dto.CredentialStatusResponse;
import io.mosip.certify.core.dto.LedgerSearchResult;
import io.mosip.certify.core.spi.CredentialLedgerService;
import io.mosip.certify.exception.ManagementApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CredentialLedgerControllerTest {

    private static final String BASE = "{\"issuerId\":\"did:web:i\",\"credentialType\":\"T\",\"indexedAttributesEquals\":{\"document_id\":\"1\"}%s}";

    private CredentialLedgerService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(CredentialLedgerService.class);
        CredentialLedgerController controller = new CredentialLedgerController();
        ReflectionTestUtils.setField(controller, "credentialLedgerService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ExceptionHandlerAdvice()).build();
    }

    private static String body(String extra) {
        return String.format(BASE, extra);
    }

    private static LedgerSearchResult<CredentialStatusResponse> result(int count, boolean hasMore, boolean paginated) {
        return new LedgerSearchResult<>(java.util.stream.IntStream.range(0, count).mapToObj(i -> new CredentialStatusResponse()).toList(), hasMore, paginated);
    }

    @Test
    void pagedResult_sendsXHasMore() throws Exception {
        when(service.searchCredentialLedger(any())).thenReturn(result(2, true, true));
        mvc.perform(post("/ledger-search").contentType(MediaType.APPLICATION_JSON).content(body(",\"limit\":2")))
                .andExpect(status().isOk()).andExpect(header().string("X-Has-More", "true")).andExpect(jsonPath("$.length()").value(2));

        when(service.searchCredentialLedgerV2(any())).thenReturn(result(1, false, true));
        mvc.perform(post("/v2/ledger-search").contentType(MediaType.APPLICATION_JSON).content(body(",\"offset\":0")))
                .andExpect(status().isOk()).andExpect(header().string("X-Has-More", "false"));
    }

    @Test
    void emptyPagedResult_is204WithXHasMoreFalse() throws Exception {
        when(service.searchCredentialLedger(any())).thenReturn(result(0, false, true));
        mvc.perform(post("/ledger-search").contentType(MediaType.APPLICATION_JSON).content(body(",\"limit\":5")))
                .andExpect(status().isNoContent()).andExpect(header().string("X-Has-More", "false"));
    }

    @Test
    void legacyRequest_hasNoPagingHeader_andKeepsTheOldStatusCodes() throws Exception {
        when(service.searchCredentialLedger(any())).thenReturn(result(1, false, false));
        mvc.perform(post("/ledger-search").contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isOk()).andExpect(header().doesNotExist("X-Has-More"));

        when(service.searchCredentialLedger(any())).thenReturn(result(0, false, false));
        mvc.perform(post("/ledger-search").contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isNoContent()).andExpect(header().doesNotExist("X-Has-More"));
    }

    @Test
    void managementErrors_areReportedWithTheirStatusAndCode() throws Exception {
        when(service.searchCredentialLedger(any())).thenThrow(
                new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.PAGINATION_REQUIRED, "send limit and offset"));
        mvc.perform(post("/ledger-search").contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("pagination_required"))
                .andExpect(jsonPath("$.error_description").value("send limit and offset"));

        when(service.searchCredentialLedgerV2(any())).thenThrow(
                new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.SEARCH_WINDOW_EXCEEDED, "narrow the search"));
        mvc.perform(post("/v2/ledger-search").contentType(MediaType.APPLICATION_JSON).content(body(",\"offset\":10001")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("search_window_exceeded"));
    }

    @Test
    void nonIntegerPagingValues_are400() throws Exception {
        for (String extra : List.of(",\"limit\":\"10\"", ",\"limit\":10.5", ",\"limit\":10.0", ",\"offset\":\"0\"",
                ",\"offset\":3000000000", ",\"limit\":true", ",\"limit\":[1]")) {
            mvc.perform(post("/ledger-search").contentType(MediaType.APPLICATION_JSON).content(body(extra)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        }
    }

    @Test
    void malformedBodyAndMissingMandatoryFields_are400() throws Exception {
        mvc.perform(post("/ledger-search").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        mvc.perform(post("/v2/ledger-search").contentType(MediaType.APPLICATION_JSON).content("{\"credentialType\":\"T\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.error_description").value(org.hamcrest.Matchers.containsString("issuerId")));
    }
}
