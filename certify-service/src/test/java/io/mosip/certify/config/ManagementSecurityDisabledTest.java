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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Management authentication left disabled: the service must fail closed. */
@SpringBootTest(classes = ManagementSecurityTestSupport.TestApp.class, properties = {
        "server.servlet.path=",
        "mosip.certify.management.auth.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("management-test")
class ManagementSecurityDisabledTest {

    @Autowired
    private MockMvc mvc;
    @MockBean
    private CredentialStatusService credentialStatusService;
    @MockBean
    private CredentialLedgerService credentialLedgerService;
    @MockBean
    private StatusListCredentialService statusListCredentialService;

    @Test
    void everyProtectedRoute_isRefused_evenWhenCalledWithAValidLookingToken() throws Exception {
        for (String path : protectedPostPaths()) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(bodyFor(path)))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(bodyFor(path))
                    .header("Authorization", "Bearer " + token().build())).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void publicStatusListRead_staysAvailable() throws Exception {
        when(statusListCredentialService.getPublishedStatusList("L1")).thenReturn(new StatusListCredentialService.PublishedStatusList("{\"list\":true}", "\"etag-1\""));
        mvc.perform(get("/credentials/status-list/L1")).andExpect(status().isOk());
    }

    @Test
    void walletRoutes_stayAvailable() throws Exception {
        mvc.perform(post("/issuance/credential")).andExpect(status().isOk());
    }
}
