package io.mosip.certify.services;

import io.mosip.certify.core.dto.CredentialStatusDetail;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * What must be remembered about a credential once it has been signed.
 *
 * @param credentialId      the credential's own id; may be null when no id prefix is configured
 * @param issuerId          the issuer (DID) that signed it
 * @param credentialType    the normalised credential type as stored in the ledger
 * @param statusDetail      the status list entry reserved for it, or null when it has none
 * @param indexedAttributes searchable attributes extracted from trusted provider data
 * @param issuanceDate      the issue time, UTC
 * @param expirationDate    the validity end that was actually rendered into the credential, UTC; may be null
 * @param ledgerEnabled     whether to write a ledger entry
 */
public record IssuanceRecord(String credentialId, String issuerId, String credentialType,
                             CredentialStatusDetail statusDetail, Map<String, Object> indexedAttributes,
                             LocalDateTime issuanceDate, LocalDateTime expirationDate, boolean ledgerEnabled) {
}
