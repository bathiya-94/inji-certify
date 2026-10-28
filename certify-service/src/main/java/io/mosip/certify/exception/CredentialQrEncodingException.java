/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package io.mosip.certify.exception;

/** A QR encoding failure after credential issuance, without credential values in the message. */
public class CredentialQrEncodingException extends RuntimeException {

    public CredentialQrEncodingException(String message) {
        super(message);
    }

    public CredentialQrEncodingException(String message, Throwable cause) {
        super(message, cause);
    }
}
