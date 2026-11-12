package io.mosip.certify.controller;

import io.mosip.certify.advice.ExceptionHandlerAdvice;
import io.mosip.certify.config.StatusListPublicationProperties;
import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.spi.CredentialStatusService;
import io.mosip.certify.exception.ManagementApiException;
import io.mosip.certify.services.StatusListCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CredentialStatusControllerTest {

    private static final String ETAG = "\"abc123\"";
    private StatusListCredentialService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(StatusListCredentialService.class);
        CredentialStatusController controller = new CredentialStatusController();
        ReflectionTestUtils.setField(controller, "statusListCredentialService", service);
        ReflectionTestUtils.setField(controller, "credentialStatusService", Mockito.mock(CredentialStatusService.class));
        ReflectionTestUtils.setField(controller, "publicationProperties", new StatusListPublicationProperties());
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ExceptionHandlerAdvice()).build();
        when(service.getPublishedStatusList("L1")).thenReturn(new StatusListCredentialService.PublishedStatusList("{\"list\":true}", ETAG));
    }

    @Test
    void servesTheListWithCacheHeadersAndAStrongEtag() throws Exception {
        mvc.perform(get("/credentials/status-list/L1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=20, s-maxage=20, must-revalidate"))
                .andExpect(header().string("ETag", ETAG))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(content().json("{\"list\":true}"));
    }

    @Test
    void matchingValidator_gets304WithoutABody_butStillCacheHeaders() throws Exception {
        for (String header : new String[]{ETAG, "W/" + ETAG, "\"other\", " + ETAG, "*"}) {
            mvc.perform(get("/credentials/status-list/L1").header("If-None-Match", header))
                    .andExpect(status().isNotModified())
                    .andExpect(header().string("ETag", ETAG))
                    .andExpect(header().string("Cache-Control", "public, max-age=20, s-maxage=20, must-revalidate"))
                    .andExpect(content().string(""));
        }
    }

    @Test
    void differentValidator_getsTheFullList() throws Exception {
        mvc.perform(get("/credentials/status-list/L1").header("If-None-Match", "\"stale\""))
                .andExpect(status().isOk()).andExpect(content().json("{\"list\":true}"));
    }

    @Test
    void unknownList_is404_andNeverCached() throws Exception {
        when(service.getPublishedStatusList("nope")).thenThrow(
                new ManagementApiException(HttpStatus.NOT_FOUND, ErrorConstants.STATUS_LIST_NOT_FOUND, "Status list not found."));
        mvc.perform(get("/credentials/status-list/nope"))
                .andExpect(status().isNotFound()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.error").value("status_list_not_found_for_the_given_id"));
    }

    @Test
    void staleList_is503_withNoStoreAndNoEtag() throws Exception {
        when(service.getPublishedStatusList("L2")).thenThrow(
                new ManagementApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorConstants.STATUS_LIST_UNAVAILABLE, "being updated"));
        mvc.perform(get("/credentials/status-list/L2"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("ETag"))
                .andExpect(jsonPath("$.error").value("status_list_unavailable"));
    }
}
