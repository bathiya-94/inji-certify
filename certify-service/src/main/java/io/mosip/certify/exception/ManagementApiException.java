package io.mosip.certify.exception;

import io.mosip.certify.core.exception.CertifyException;
import org.springframework.http.HttpStatus;

/**
 * A failure of an administrative or status-list endpoint that must be reported with a specific HTTP status
 * and an {@code error} / {@code error_description} body.
 */
public class ManagementApiException extends CertifyException {

    private final HttpStatus status;

    public ManagementApiException(HttpStatus status, String errorCode, String description) {
        super(errorCode, description);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
