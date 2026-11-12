package io.mosip.certify.config;

import io.mosip.certify.core.spi.CredentialLedgerService;
import io.mosip.certify.core.spi.CredentialStatusService;
import io.mosip.certify.services.StatusListCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static io.mosip.certify.config.ManagementSecurityTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The explicit local-development override: the protected routes open, nothing else does. */
@SpringBootTest(classes = ManagementSecurityTestSupport.TestApp.class, properties = {
        "server.servlet.path=",
        "mosip.certify.management.auth.enabled=false",
        "mosip.certify.management.auth.allow-unauthenticated=true",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("management-test")
class ManagementSecurityOverrideTest {

    @Autowired
    private MockMvc mvc;
    @MockBean
    private CredentialStatusService credentialStatusService;
    @MockBean
    private CredentialLedgerService credentialLedgerService;
    @MockBean
    private StatusListCredentialService statusListCredentialService;

    @Test
    void protectedPostRoutes_openWithoutAToken() throws Exception {
        when(credentialLedgerService.searchCredentialLedger(any())).thenReturn(new io.mosip.certify.core.dto.LedgerSearchResult<>(List.of(), false, false));
        mvc.perform(post("/ledger-search").contentType(MediaType.APPLICATION_JSON).content(SEARCH_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    void unlistedRoutes_remainDenied() throws Exception {
        mvc.perform(delete("/credentials/status")).andExpect(status().isUnauthorized());
    }
}
