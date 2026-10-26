package io.mosip.certify.api.exception;
/** Deliberately excludes submitted values from the error message. */
public class LookupValidationException extends RuntimeException {
    public LookupValidationException(String message) { super(message); }
}
