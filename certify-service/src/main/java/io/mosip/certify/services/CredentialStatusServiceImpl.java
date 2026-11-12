package io.mosip.certify.services;

import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.dto.CredentialStatusDetail;
import io.mosip.certify.core.dto.CredentialStatusResponse;
import io.mosip.certify.core.dto.UpdateCredentialStatusRequest;
import io.mosip.certify.core.dto.UpdateCredentialStatusRequestV2;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.core.spi.CredentialStatusService;
import io.mosip.certify.entity.CredentialStatusTransaction;
import io.mosip.certify.entity.Ledger;
import io.mosip.certify.entity.StatusListAvailableIndices;
import io.mosip.certify.entity.StatusListCredential;
import io.mosip.certify.exception.ManagementApiException;
import io.mosip.certify.repository.CredentialStatusTransactionRepository;
import io.mosip.certify.repository.LedgerRepository;
import io.mosip.certify.repository.StatusListAvailableIndicesRepository;
import io.mosip.certify.repository.StatusListCredentialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Accepts status changes (revocations). Success means the change was queued; it reaches the published status list
 * asynchronously, within the publication interval.
 */
@Slf4j
@Service
public class CredentialStatusServiceImpl implements CredentialStatusService {

    private static final String REVOCATION = "revocation";
    private static final long BITS_PER_KB = 8192L;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private CredentialStatusTransactionRepository credentialStatusTransactionRepository;

    @Autowired
    private StatusListCredentialRepository statusListCredentialRepository;

    @Autowired
    private StatusListAvailableIndicesRepository positionRepository;

    @Value("#{${mosip.certify.data-provider-plugin.credential-status.allowed-status-purposes:{}}}")
    private List<String> allowedCredentialStatusPurposes;

    @Override
    @Transactional
    public CredentialStatusResponse updateCredentialStatus(UpdateCredentialStatusRequest request) {
        Ledger ledger = ledgerRepository.findByCredentialId(request.getCredentialId())
                .orElseThrow(() -> new ManagementApiException(HttpStatus.NOT_FOUND, ErrorConstants.CREDENTIAL_NOT_FOUND,
                        "Credential not found: " + request.getCredentialId()));

        if (ledger.getCredentialStatusDetails() == null || ledger.getCredentialStatusDetails().isEmpty()) {
            throw new CertifyException(ErrorConstants.MISSING_CREDENTIAL_STATUS_DETAILS, "CredentialStatus details are missing in the issued credential.");
        }

        UpdateCredentialStatusRequest.CredentialStatusDto requested = request.getCredentialStatus();
        CredentialStatusDetail detail = selectStatusEntry(ledger.getCredentialStatusDetails(), requested.getStatusPurpose());
        rejectContradictingReference(detail, requested);
        requireAllowedPurpose(detail.getStatusPurpose());
        rejectUndoingRevocation(detail.getStatusPurpose(), request.getStatus());

        CredentialStatusTransaction transaction = record(ledger.getCredentialId(), detail.getStatusPurpose(),
                request.getStatus(), detail.getStatusListCredentialId(), detail.getStatusListIndex());

        CredentialStatusResponse dto = new CredentialStatusResponse();
        dto.setCredentialId(ledger.getCredentialId());
        dto.setIssuerId(ledger.getIssuerId());
        dto.setCredentialType(ledger.getCredentialType());
        dto.setIssueDate(ledger.getIssuanceDate());
        dto.setExpirationDate(ledger.getExpirationDate());
        dto.setStatusListCredentialUrl(detail.getStatusListCredentialId());
        dto.setStatusListIndex(detail.getStatusListIndex());
        dto.setStatusPurpose(detail.getStatusPurpose());
        dto.setStatusTimestamp(transaction.getCreatedDtimes());
        return dto;
    }

    @Override
    @Transactional
    public CredentialStatusResponse updateCredentialStatusV2(UpdateCredentialStatusRequestV2 request) {
        UpdateCredentialStatusRequestV2.CredentialStatusDtoV2 requested = request.getCredentialStatus();
        String statusListCredentialId = requested.getStatusListCredential();
        Long statusListIndex = requested.getStatusListIndex();

        if (requested.getId() != null && !requested.getId().equals(statusListCredentialId)) {
            throw new CertifyException(ErrorConstants.STATUS_ID_MISMATCH, "Mismatch between credential status ID and Status List Credential.");
        }
        StatusListCredential statusList = statusListCredentialRepository.findById(statusListCredentialId)
                .orElseThrow(() -> new ManagementApiException(HttpStatus.NOT_FOUND, ErrorConstants.STATUS_LIST_NOT_FOUND,
                        "Status List Credential not found for ID: " + statusListCredentialId));

        String purpose = requested.getStatusPurpose() == null ? statusList.getStatusPurpose() : requested.getStatusPurpose();
        if (purpose != null && statusList.getStatusPurpose() != null && !purpose.equals(statusList.getStatusPurpose())) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.STATUS_PURPOSE_MISMATCH,
                    "The status list is for '" + statusList.getStatusPurpose() + "', not '" + purpose + "'.");
        }
        long capacityBits = statusList.getCapacityInKB() == null ? 0 : statusList.getCapacityInKB() * BITS_PER_KB;
        if (statusListIndex < 0 || statusListIndex >= capacityBits) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.INDEX_OUT_OF_BOUNDS,
                    "statusListIndex " + statusListIndex + " is outside the status list (0 to " + (capacityBits - 1) + ").");
        }
        StatusListAvailableIndices position = positionRepository
                .findByStatusListCredentialIdAndListIndex(statusListCredentialId, statusListIndex).orElse(null);
        if (position == null || !Boolean.TRUE.equals(position.getIsAssigned()) || position.getIssuedAt() == null) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.CREDENTIAL_NOT_ISSUED,
                    "No issued credential uses position " + statusListIndex + " of this status list.");
        }
        requireAllowedPurpose(purpose);
        rejectUndoingRevocation(purpose, request.getStatus());

        CredentialStatusTransaction transaction = record(position.getCredentialId(), purpose, request.getStatus(),
                statusListCredentialId, statusListIndex);

        CredentialStatusResponse dto = new CredentialStatusResponse();
        dto.setStatusListCredentialUrl(statusListCredentialId);
        dto.setStatusListIndex(statusListIndex);
        dto.setStatusPurpose(purpose);
        dto.setStatusTimestamp(transaction.getCreatedDtimes());
        if (requested.getType() != null) {
            dto.setCredentialType(requested.getType());
        }
        return dto;
    }

    /** The credential's status entry for the requested purpose; without a purpose the entry must be unambiguous. */
    private CredentialStatusDetail selectStatusEntry(List<CredentialStatusDetail> details, String requestedPurpose) {
        if (requestedPurpose != null) {
            return details.stream().filter(d -> requestedPurpose.equals(d.getStatusPurpose())).findFirst()
                    .orElseThrow(() -> new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.STATUS_PURPOSE_MISMATCH,
                            "The credential has no status entry for purpose '" + requestedPurpose + "'."));
        }
        if (details.size() > 1) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.AMBIGUOUS_STATUS_PURPOSE,
                    "The credential has several status entries; send statusPurpose.");
        }
        return details.get(0);
    }

    /** A request that names a list or position other than the credential's own is a mistake, not a hint. */
    private void rejectContradictingReference(CredentialStatusDetail detail, UpdateCredentialStatusRequest.CredentialStatusDto requested) {
        if (requested.getStatusListIndex() != null && !requested.getStatusListIndex().equals(detail.getStatusListIndex())) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.STATUS_ID_MISMATCH,
                    "statusListIndex does not match the credential's status entry.");
        }
        if (requested.getStatusListCredential() != null
                && !lastSegment(requested.getStatusListCredential()).equals(lastSegment(detail.getStatusListCredentialId()))) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.STATUS_ID_MISMATCH,
                    "statusListCredential does not match the credential's status entry.");
        }
    }

    private void requireAllowedPurpose(String purpose) {
        if (purpose != null && allowedCredentialStatusPurposes != null && !allowedCredentialStatusPurposes.isEmpty()
                && !allowedCredentialStatusPurposes.contains(purpose)) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.STATUS_PURPOSE_NOT_ALLOWED,
                    "Status purpose '" + purpose + "' is not enabled.");
        }
    }

    private void rejectUndoingRevocation(String purpose, Boolean status) {
        if (REVOCATION.equals(purpose) && !Boolean.TRUE.equals(status)) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.REVOCATION_CANNOT_BE_UNDONE,
                    "A revoked credential cannot be restored.");
        }
    }

    /**
     * Queues the change. A repeated revocation of the same position is not queued again: the earlier request is
     * returned, so concurrent or repeated calls are harmless.
     */
    private CredentialStatusTransaction record(String credentialId, String purpose, Boolean status, String listId, Long index) {
        if (REVOCATION.equals(purpose) && Boolean.TRUE.equals(status)) {
            credentialStatusTransactionRepository.insertRevocationIfAbsent(credentialId, listId, index,
                    LocalDateTime.now(ZoneOffset.UTC));
            return credentialStatusTransactionRepository
                    .findFirstByStatusListCredentialIdAndStatusListIndexAndStatusPurposeAndStatusValueTrueOrderByTransactionLogIdAsc(
                            listId, index, REVOCATION)
                    .orElseThrow(() -> new CertifyException(ErrorConstants.STATUS_LIST_UPDATE_FAILED, "The revocation could not be recorded."));
        }
        CredentialStatusTransaction transaction = new CredentialStatusTransaction();
        transaction.setCredentialId(credentialId);
        transaction.setStatusPurpose(purpose);
        transaction.setStatusValue(status);
        transaction.setStatusListCredentialId(listId);
        transaction.setStatusListIndex(index);
        return credentialStatusTransactionRepository.save(transaction);
    }

    private static String lastSegment(String value) {
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash < value.length() - 1 ? value.substring(slash + 1) : value;
    }
}
