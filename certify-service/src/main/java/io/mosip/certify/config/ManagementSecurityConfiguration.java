package io.mosip.certify.config;

import io.mosip.certify.services.ManagementCallerAuthorizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Protects the administrative endpoints (revocation and ledger search) with their own bearer-token trust.
 * <p>
 * The chain always owns {@code /credentials/**}, {@code /ledger-search/**} and {@code /v2/ledger-search/**}, so
 * a deployment that forgets to enable authentication gets a closed service instead of an open one. Within
 * those paths only the public status list read is open; anything not listed is denied.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@EnableConfigurationProperties(ManagementAuthProperties.class)
public class ManagementSecurityConfiguration {

    static final String REVOKE_SCOPE = "certify:credentials:revoke";
    static final String SEARCH_SCOPE = "certify:ledger:search";

    @Bean
    public ManagementCallerAuthorizer managementCallerAuthorizer(ManagementAuthProperties properties) {
        return new ManagementCallerAuthorizer(properties);
    }

    /** Validates signature, issuer, audience and expiry; every key-fetch or decoding problem is a 401. */
    @Bean("managementJwtDecoder")
    public JwtDecoder managementJwtDecoder(ManagementAuthProperties properties) {
        properties.validate();
        if (!properties.isEnabled()) {
            // No token can be trusted while management authentication is off.
            return token -> {
                throw new BadJwtException("Management authentication is not enabled");
            };
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
        OAuth2TokenValidator<Jwt> audienceAndExpiry = jwt -> jwt.getAudience() != null
                && jwt.getAudience().contains(properties.getAudience())
                && jwt.getExpiresAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token",
                        "Management audience and expiry are required", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getIssuer()), audienceAndExpiry));
        return token -> {
            try {
                return decoder.decode(token);
            } catch (BadJwtException e) {
                throw e;
            } catch (JwtException e) {
                // For example the key server cannot be reached: fail closed with 401.
                throw new BadJwtException("Management token validation failed", e);
            }
        };
    }

    @Bean
    @Order(2)
    public SecurityFilterChain managementChain(HttpSecurity http,
                                               @Qualifier("managementJwtDecoder") JwtDecoder decoder,
                                               ManagementCallerAuthorizer authorizer,
                                               ManagementAuthProperties properties,
                                               @Value("${server.servlet.path:/v1/certify}") String servletPath) throws Exception {
        RequestMatcher owned = new OrRequestMatcher(
                matcher(servletPath, "/credentials/**", null),
                matcher(servletPath, "/ledger-search/**", null),
                matcher(servletPath, "/v2/ledger-search/**", null));

        http.securityMatcher(owned)
                .csrf(csrf -> csrf.disable()) // bearer-token only, no browser session
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    // 1. The public status list read stays open: verifiers fetch it without credentials.
                    auth.requestMatchers(matcher(servletPath, "/credentials/status-list/*", HttpMethod.GET)).permitAll();
                    // 2. The administrative calls need a token and their own scope.
                    protect(auth, properties, authorizer, REVOKE_SCOPE,
                            matcher(servletPath, "/credentials/status", HttpMethod.POST),
                            matcher(servletPath, "/credentials/v2/status", HttpMethod.POST));
                    protect(auth, properties, authorizer, SEARCH_SCOPE,
                            matcher(servletPath, "/ledger-search", HttpMethod.POST),
                            matcher(servletPath, "/v2/ledger-search", HttpMethod.POST));
                    // 3. Everything else under these paths is denied.
                    auth.anyRequest().denyAll();
                })
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder)));
        return http.build();
    }

    private static void protect(AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth,
                                ManagementAuthProperties properties, ManagementCallerAuthorizer authorizer, String scope,
                                RequestMatcher... matchers) {
        var rule = auth.requestMatchers(matchers);
        if (properties.isEnabled()) {
            rule.access(authorizer.requiring(scope));
        } else if (properties.isAllowUnauthenticated()) {
            rule.permitAll();
        } else {
            rule.denyAll();
        }
    }

    private static AntPathRequestMatcher matcher(String servletPath, String path, HttpMethod method) {
        return new AntPathRequestMatcher(servletPath + path, method == null ? null : method.name());
    }
}
