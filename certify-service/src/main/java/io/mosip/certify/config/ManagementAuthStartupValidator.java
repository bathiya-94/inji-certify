package io.mosip.certify.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Refuses to start a service that records issued credentials or manages their status while the
 * administrative endpoints are unprotected, unless that is explicitly allowed (local development only).
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@EnableConfigurationProperties(ManagementAuthProperties.class)
public class ManagementAuthStartupValidator {

    private final ManagementAuthProperties properties;
    private final boolean ledgerEnabled;
    private final List<String> statusPurposes;

    public ManagementAuthStartupValidator(ManagementAuthProperties properties,
                                          @Value("#{${mosip.certify.issuer.ledger-enabled:true}}") boolean ledgerEnabled,
                                          @Value("#{${mosip.certify.data-provider-plugin.credential-status.allowed-status-purposes:{}}}") List<String> statusPurposes) {
        this.properties = properties;
        this.ledgerEnabled = ledgerEnabled;
        this.statusPurposes = statusPurposes;
    }

    @PostConstruct
    void validate() {
        properties.validate();
        boolean managesCredentials = ledgerEnabled || (statusPurposes != null && !statusPurposes.isEmpty());
        if (properties.isEnabled() || !managesCredentials) {
            return;
        }
        if (!properties.isAllowUnauthenticated()) {
            throw new IllegalStateException("The ledger or credential status is in use but "
                    + "mosip.certify.management.auth.enabled is false. Enable management authentication, or set "
                    + "mosip.certify.management.auth.allow-unauthenticated=true for local development only.");
        }
        log.warn("Management authentication is OFF and unauthenticated access is allowed: revocation and ledger "
                + "search are open to anyone who can reach this service. Use this only for local development.");
    }
}
