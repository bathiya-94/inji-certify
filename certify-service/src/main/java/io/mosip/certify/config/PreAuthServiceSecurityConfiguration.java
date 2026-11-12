package io.mosip.certify.config;

import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import io.mosip.certify.services.PreAuthCallerAuthorizer;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "mosip.certify.integration.data-provider-plugin", havingValue = "RouterDataProviderPlugin")
@EnableConfigurationProperties(PreAuthServiceAuthProperties.class)
public class PreAuthServiceSecurityConfiguration {
    @Bean("preAuthServiceJwtDecoder")
    public JwtDecoder preAuthServiceJwtDecoder(PreAuthServiceAuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
        OAuth2TokenValidator<Jwt> audienceAndExpiry = jwt -> jwt.getAudience() != null
                && jwt.getAudience().contains(properties.getAudience())
                && jwt.getExpiresAt() != null
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Audience and expiry are required", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.getIssuer()), audienceAndExpiry));
        return decoder;
    }
    @Bean
    @Order(1)
    public SecurityFilterChain preAuthServiceChain(HttpSecurity http,
            @Qualifier("preAuthServiceJwtDecoder") JwtDecoder decoder,
            @Value("${server.servlet.path:/v1/certify}") String servletPath) throws Exception {
        return http.securityMatcher(new AntPathRequestMatcher(servletPath + "/pre-authorized-data/**"))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("SCOPE_certify:preauth:create"))
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder))).build();
    }
    @Bean
    public PreAuthCallerAuthorizer preAuthCallerAuthorizer(PreAuthServiceAuthProperties properties) {
        return new PreAuthCallerAuthorizer(properties);
    }
}
