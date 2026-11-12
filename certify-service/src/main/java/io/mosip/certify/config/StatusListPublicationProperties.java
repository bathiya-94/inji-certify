package io.mosip.certify.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** How long verifiers may cache a status list and when an unpublished change makes the list unavailable. */
@Component
@ConfigurationProperties("mosip.certify.status-list")
@Getter
@Setter
public class StatusListPublicationProperties {

    /** Signed {@code credentialSubject.ttl} and the HTTP cache lifetime of the public list, in milliseconds. */
    private long ttlMs = 20000;

    /**
     * A list with a change that has waited longer than this is reported as unavailable instead of being served
     * as current. It must exceed the publication interval, or the error would appear during normal operation.
     */
    private long maxUnpublishedAgeSeconds = 90;

    @PostConstruct
    void validate() {
        if (ttlMs < 1000) {
            throw new IllegalStateException("mosip.certify.status-list.ttl-ms must be at least 1000");
        }
        if (maxUnpublishedAgeSeconds < 1) {
            throw new IllegalStateException("mosip.certify.status-list.max-unpublished-age-seconds must be positive");
        }
    }

    /** The whole seconds a cache may keep the list. */
    public long cacheSeconds() {
        return ttlMs / 1000;
    }
}
