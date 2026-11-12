package io.mosip.certify.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.mosip.certify.controller.CredentialLedgerController;
import io.mosip.certify.controller.CredentialStatusController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/** Real signed tokens and a local key server, shared by the management security tests. */
final class ManagementSecurityTestSupport {

    static final String ISSUER = "https://mock-idp.test";
    static final String AUDIENCE = "certify-management";
    static final String CLIENT = "erl-admin-backend";
    static final String REVOKE = "certify:credentials:revoke";
    static final String SEARCH = "certify:ledger:search";

    static final RSAKey KEY;
    static final RSAKey OTHER_KEY;
    static final String JWKS_URI;

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("management-key").generate();
            OTHER_KEY = new RSAKeyGenerator(2048).keyID("management-key").generate(); // same kid, different key
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] jwks = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks.json", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                exchange.getResponseBody().write(jwks);
                exchange.close();
            });
            server.start();
            JWKS_URI = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json";
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private ManagementSecurityTestSupport() {
    }

    /** Builder for a token that is valid unless a field is changed. */
    static final class Token {
        String issuer = ISSUER;
        String audience = AUDIENCE;
        String clientClaim = "client_id";
        String client = CLIENT;
        String scope = REVOKE + " " + SEARCH;
        long expiresInSeconds = 300;
        boolean includeExpiry = true;
        RSAKey signingKey = KEY;

        Token issuer(String value) { issuer = value; return this; }
        Token audience(String value) { audience = value; return this; }
        Token client(String claim, String value) { clientClaim = claim; client = value; return this; }
        Token scope(String value) { scope = value; return this; }
        Token expiresIn(long seconds) { expiresInSeconds = seconds; return this; }
        Token withoutExpiry() { includeExpiry = false; return this; }
        Token signedWith(RSAKey key) { signingKey = key; return this; }

        String build() {
            try {
                JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("svc");
                if (client != null) claims.claim(clientClaim, client);
                if (scope != null) claims.claim("scope", scope);
                if (includeExpiry) claims.expirationTime(new Date(System.currentTimeMillis() + expiresInSeconds * 1000));
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(), claims.build());
                jwt.sign(new RSASSASigner(signingKey));
                return jwt.serialize();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    static Token token() {
        return new Token();
    }

    /** The management chain under test, the controllers it protects, and a stand-in for the wallet routes. */
    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({ManagementSecurityConfiguration.class, CredentialStatusController.class, CredentialLedgerController.class,
            WalletStub.class, StatusListPublicationProperties.class})
    static class TestApp {
        /** Stands in for the application's default chain, which owns every route the management chain does not. */
        @Bean
        SecurityFilterChain defaultChain(HttpSecurity http) throws Exception {
            return http.csrf(csrf -> csrf.disable()).authorizeHttpRequests(a -> a.anyRequest().permitAll()).build();
        }
    }

    @RestController
    @RequestMapping("/issuance")
    static class WalletStub {
        @PostMapping("/credential")
        String credential() {
            return "wallet";
        }
    }

    static final String REVOKE_V1_BODY = """
            {"credentialId":"urn:uuid:1","status":true,
             "credentialStatus":{"statusPurpose":"revocation","statusListIndex":7,"statusListCredential":"L1"}}""";
    static final String REVOKE_V2_BODY = """
            {"status":true,"credentialStatus":{"statusPurpose":"revocation","statusListIndex":7,"statusListCredential":"L1"}}""";
    static final String SEARCH_BODY = """
            {"issuerId":"did:web:issuer","credentialType":"ERLCredential,VerifiableCredential","indexedAttributesEquals":{"document_id":"42"}}""";

    static List<String> protectedPostPaths() {
        return List.of("/credentials/status", "/credentials/v2/status", "/ledger-search", "/v2/ledger-search");
    }

    static String bodyFor(String path) {
        return switch (path) {
            case "/credentials/status" -> REVOKE_V1_BODY;
            case "/credentials/v2/status" -> REVOKE_V2_BODY;
            default -> SEARCH_BODY;
        };
    }

    static String scopeFor(String path) {
        return path.contains("ledger-search") ? SEARCH : REVOKE;
    }
}
