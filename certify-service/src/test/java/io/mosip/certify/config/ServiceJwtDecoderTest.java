package io.mosip.certify.config;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Date;
import org.junit.Test;
import org.springframework.security.oauth2.jwt.*;
import static org.junit.Assert.*;

public class ServiceJwtDecoderTest {
    @Test public void validatesServiceSignatureIssuerAudienceAndRequiredExpiry() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("service").generate();
        HttpServer jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwks.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        jwks.start();
        try {
            PreAuthServiceAuthProperties properties = new PreAuthServiceAuthProperties();
            properties.setIssuer("https://service.example"); properties.setAudience("certify-internal");
            properties.setJwkSetUri("http://127.0.0.1:" + jwks.getAddress().getPort() + "/jwks");
            JwtDecoder decoder = new PreAuthServiceSecurityConfiguration().preAuthServiceJwtDecoder(properties);
            String valid = token(key, "https://service.example", "certify-internal", Instant.now().plusSeconds(120));
            assertEquals("erl-client", decoder.decode(valid).getClaimAsString("client_id"));
            assertThrows(JwtException.class, () -> decoder.decode(token(key, "https://other.example", "certify-internal", Instant.now().plusSeconds(120))));
            assertThrows(JwtException.class, () -> decoder.decode(token(key, "https://service.example", "wallet", Instant.now().plusSeconds(120))));
            assertThrows(JwtException.class, () -> decoder.decode(token(key, "https://service.example", "certify-internal", Instant.now().minusSeconds(120))));
            assertThrows(JwtException.class, () -> decoder.decode(token(key, "https://service.example", "certify-internal", null)));
            assertThrows(JwtException.class, () -> decoder.decode(token(key, "https://service.example", null, Instant.now().plusSeconds(120))));
            RSAKey other = new RSAKeyGenerator(2048).keyID("service").generate();
            assertThrows(JwtException.class, () -> decoder.decode(token(other, "https://service.example", "certify-internal", Instant.now().plusSeconds(120))));
        } finally { jwks.stop(0); }
    }
    private String token(RSAKey key, String issuer, String audience, Instant expiry) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience)
                .issueTime(Date.from(Instant.now())).claim("client_id", "erl-client").claim("scope", "certify:preauth:create");
        if (expiry != null) claims.expirationTime(Date.from(expiry));
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("service").build(), claims.build());
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }
}
