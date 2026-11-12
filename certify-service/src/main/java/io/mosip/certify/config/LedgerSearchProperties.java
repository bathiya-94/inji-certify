package io.mosip.certify.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bounds for ledger search paging. */
@Component
@ConfigurationProperties("mosip.certify.ledger-search")
@Getter
@Setter
public class LedgerSearchProperties {
    private int defaultLimit = 100;
    private int maxLimit = 1000;
    private int maxOffset = 10000;
    /** Most records returned when the caller sends no paging fields; more than this is refused. */
    private int legacyLimit = 1000;

    @PostConstruct
    void validate() {
        if (defaultLimit < 1 || maxLimit < 1 || defaultLimit > maxLimit) {
            throw new IllegalStateException("mosip.certify.ledger-search: 1 <= default-limit <= max-limit is required");
        }
        if (maxOffset < 0) {
            throw new IllegalStateException("mosip.certify.ledger-search.max-offset must not be negative");
        }
        if (legacyLimit < 1 || legacyLimit == Integer.MAX_VALUE) {
            throw new IllegalStateException("mosip.certify.ledger-search.legacy-limit must be positive and below the integer maximum");
        }
    }
}
