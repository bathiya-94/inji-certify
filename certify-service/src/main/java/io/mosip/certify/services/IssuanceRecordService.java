package io.mosip.certify.services;

import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.core.spi.CredentialLedgerService;
import io.mosip.certify.repository.StatusListAvailableIndicesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Records a successful issuance. The ledger entry and the confirmation of the credential's status list
 * position commit together; if either fails the signed credential must not be returned.
 */
@Slf4j
@Service
public class IssuanceRecordService {

    @Autowired
    private CredentialLedgerService credentialLedgerService;

    @Autowired
    private StatusListAvailableIndicesRepository positionRepository;

    @Transactional
    public void record(IssuanceRecord record) {
        if (record.ledgerEnabled()) {
            credentialLedgerService.storeLedgerEntry(record.credentialId(), record.issuerId(), record.credentialType(),
                    record.statusDetail(), record.indexedAttributes(), record.issuanceDate(), record.expirationDate());
            log.info("Stored the credential issuance data in ledger with credentialType: {}", record.credentialType());
        }
        // Confirm the position even when the ledger is disabled, so revocation by list and index does not
        // depend on the ledger being available.
        if (record.statusDetail() != null) {
            int updated = positionRepository.confirmIssued(record.statusDetail().getStatusListCredentialId(),
                    record.statusDetail().getStatusListIndex(), record.credentialId(),
                    LocalDateTime.now(ZoneOffset.UTC));
            if (updated != 1) {
                throw new CertifyException(ErrorConstants.ISSUANCE_RECORD_FAILED,
                        "The status list position was not reserved for this credential.");
            }
        }
    }
}
