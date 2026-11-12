package io.mosip.certify.config;

import io.mosip.certify.core.dto.CredentialStatusResponse;
import io.mosip.certify.core.spi.CredentialLedgerService;
import io.mosip.certify.core.spi.CredentialStatusService;
import io.mosip.certify.services.StatusListCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static io.mosip.certify.config.ManagementSecurityTestSupport.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Management authentication enabled: every rule of the chain, with real signed tokens. */
@SpringBootTest(classes = ManagementSecurityTestSupport.TestApp.class, properties = {
        "server.servlet.path=",
        "mosip.certify.management.auth.enabled=true",
        "mosip.certify.management.auth.issuer=" + ISSUER,
        "mosip.certify.management.auth.audience=" + AUDIENCE,
        "mosip.certify.management.auth.allowed-clients=" + CLIENT + ",other-allowed-client",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("management-test")
class ManagementSecurityTest {

    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add("mosip.certify.management.auth.jwk-set-uri", () -> JWKS_URI);
    }

    @Autowired
    private MockMvc mvc;
    @MockBean
    private CredentialStatusService credentialStatusService;
    @MockBean
    private CredentialLedgerService credentialLedgerService;
    @MockBean
    private StatusListCredentialService statusListCredentialService;

    private MockHttpServletRequestBuilder post(String path, String token) {
        MockHttpServletRequestBuilder request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(path).contentType(MediaType.APPLICATION_JSON).content(bodyFor(path));
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    // ---- allowed ------------------------------------------------------------------------------

    @Test
    void validTokenWithTheRightScope_reachesEachProtectedRoute() throws Exception {
        when(credentialStatusService.updateCredentialStatus(any())).thenReturn(new CredentialStatusResponse());
        when(credentialStatusService.updateCredentialStatusV2(any())).thenReturn(new CredentialStatusResponse());
        when(credentialLedgerService.searchCredentialLedger(any())).thenReturn(new io.mosip.certify.core.dto.LedgerSearchResult<>(List.of(), false, false));
        when(credentialLedgerService.searchCredentialLedgerV2(any())).thenReturn(new io.mosip.certify.core.dto.LedgerSearchResult<>(List.of(), false, false));

        for (String path : protectedPostPaths()) {
            int expected = path.contains("ledger-search") ? 204 : 200;
            mvc.perform(post(path, token().scope(scopeFor(path)).build())).andExpect(status().is(expected));
        }
    }

    @Test
    void anyAllowedClientMayCall() throws Exception {
        when(credentialLedgerService.searchCredentialLedger(any())).thenReturn(new io.mosip.certify.core.dto.LedgerSearchResult<>(List.of(), false, false));
        mvc.perform(post("/ledger-search", token().client("client_id", "other-allowed-client").build())).andExpect(status().isNoContent());
    }

    @Test
    void publicStatusListRead_needsNoToken() throws Exception {
        when(statusListCredentialService.getPublishedStatusList("L1")).thenReturn(new StatusListCredentialService.PublishedStatusList("{\"list\":true}", "\"etag-1\""));
        mvc.perform(get("/credentials/status-list/L1")).andExpect(status().isOk());
    }

    @Test
    void walletRoutes_areNotTouchedByTheManagementChain() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/issuance/credential"))
                .andExpect(status().isOk());
    }

    // ---- 401: no or invalid authentication ------------------------------------------------------

    @Test
    void missingToken_is401_onEveryProtectedRoute() throws Exception {
        for (String path : protectedPostPaths()) {
            mvc.perform(post(path, null)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void invalidTokens_are401() throws Exception {
        List<String> bad = List.of(
                token().expiresIn(-60).build(),
                token().withoutExpiry().build(),
                token().issuer("https://someone-else.test").build(),
                token().audience("certify-wallet").build(),
                token().signedWith(OTHER_KEY).build(),
                "not-a-jwt");
        for (String token : bad) {
            mvc.perform(post("/credentials/status", token)).andExpect(status().isUnauthorized());
            mvc.perform(post("/ledger-search", token)).andExpect(status().isUnauthorized());
        }
    }

    // ---- 403: valid token, not permitted ------------------------------------------------------

    @Test
    void clientNotOnTheAllowList_is403() throws Exception {
        mvc.perform(post("/credentials/status", token().client("client_id", "intruder").build())).andExpect(status().isForbidden());
    }

    @Test
    void missingOrBlankClientClaim_is403() throws Exception {
        mvc.perform(post("/credentials/status", token().client("client_id", null).build())).andExpect(status().isForbidden());
        mvc.perform(post("/credentials/status", token().client("client_id", " ").build())).andExpect(status().isForbidden());
    }

    @Test
    void clientIsReadOnlyFromTheConfiguredClaim_withNoFallback() throws Exception {
        // azp and sub name an allowed client, but the configured claim (client_id) is absent.
        mvc.perform(post("/credentials/status", token().client("azp", CLIENT).build())).andExpect(status().isForbidden());
    }

    @Test
    void scopesAreIsolated() throws Exception {
        mvc.perform(post("/credentials/status", token().scope(SEARCH).build())).andExpect(status().isForbidden());
        mvc.perform(post("/credentials/v2/status", token().scope(SEARCH).build())).andExpect(status().isForbidden());
        mvc.perform(post("/ledger-search", token().scope(REVOKE).build())).andExpect(status().isForbidden());
        mvc.perform(post("/v2/ledger-search", token().scope(REVOKE).build())).andExpect(status().isForbidden());
        mvc.perform(post("/credentials/status", token().scope(null).build())).andExpect(status().isForbidden());
    }

    // ---- everything else under the owned paths is denied -----------------------------------------

    @Test
    void unlistedPathsAndMethods_areDenied_evenWithAFullyPrivilegedToken() throws Exception {
        String full = token().build();
        mvc.perform(get("/credentials/anything-else").header("Authorization", "Bearer " + full)).andExpect(status().isForbidden());
        mvc.perform(put("/credentials/status").header("Authorization", "Bearer " + full)).andExpect(status().isForbidden());
        mvc.perform(get("/credentials/status").header("Authorization", "Bearer " + full)).andExpect(status().isForbidden());
        mvc.perform(delete("/ledger-search").header("Authorization", "Bearer " + full)).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/credentials/status-list/L1")
                .header("Authorization", "Bearer " + full)).andExpect(status().isForbidden());
        mvc.perform(get("/credentials/anything-else")).andExpect(status().isUnauthorized());
    }
}
