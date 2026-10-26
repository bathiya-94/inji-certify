package io.mosip.certify.config;

import java.util.*;
import java.time.Instant;
import io.mosip.certify.services.PreAuthCallerAuthorizer;
import org.junit.*;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.Assert.*;

@RunWith(SpringRunner.class)
@WebAppConfiguration
@ContextConfiguration(classes = PreAuthServiceSecurityTest.Config.class)
@TestPropertySource(properties = {"mosip.certify.integration.data-provider-plugin=RouterDataProviderPlugin",
    "mosip.certify.pre-auth.service-auth.issuer=https://service.example",
    "mosip.certify.pre-auth.service-auth.jwk-set-uri=https://service.example/jwks",
    "mosip.certify.pre-auth.service-auth.audience=certify-internal",
    "mosip.certify.pre-auth.service-auth.allowed-clients[ERLCredential]=erl-client"})
public class PreAuthServiceSecurityTest {
    @Configuration @EnableWebSecurity @EnableWebMvc
    @Import(PreAuthServiceSecurityConfiguration.class)
    static class Config {
        @Bean @Primary JwtDecoder testJwtDecoder() { return mock(JwtDecoder.class); }
        @Bean Endpoint endpoint(PreAuthCallerAuthorizer authorizer) { return new Endpoint(authorizer); }
    }
    @RestController static class Endpoint {
        private final PreAuthCallerAuthorizer authorizer;
        Endpoint(PreAuthCallerAuthorizer authorizer) { this.authorizer = authorizer; }
        @PostMapping("/pre-authorized-data") String create() { authorizer.authorize("ERLCredential"); return "ok"; }
    }
    @Autowired WebApplicationContext context;
    @Autowired PreAuthCallerAuthorizer authorizer;
    MockMvc mvc;
    @Before public void setup() { mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    @After public void clear() { SecurityContextHolder.clearContext(); }
    @Test public void missingBearerTokenIsRejectedBeforeController() throws Exception {
        mvc.perform(post("/v1/certify/pre-authorized-data").servletPath("/v1/certify").pathInfo("/pre-authorized-data"))
            .andExpect(status().isUnauthorized());
    }
    @Test public void callerRequiresBothScopeAndCredentialAllowlist() {
        Jwt allowed = jwt("erl-client");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(allowed,
            List.of(new SimpleGrantedAuthority("SCOPE_certify:preauth:create"))));
        authorizer.authorize("ERLCredential");
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> authorizer.authorize("OTHER"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt("other-client"),
            List.of(new SimpleGrantedAuthority("SCOPE_certify:preauth:create"))));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> authorizer.authorize("ERLCredential"));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(allowed, List.of()));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> authorizer.authorize("ERLCredential"));
        Jwt missingClient = Jwt.withTokenValue("test").header("alg", "RS256").subject("service").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(missingClient,
            List.of(new SimpleGrantedAuthority("SCOPE_certify:preauth:create"))));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> authorizer.authorize("OTHER"));
    }
    private Jwt jwt(String client) { return Jwt.withTokenValue("test").header("alg", "RS256").subject("service")
        .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).claim("client_id", client).build(); }
}
