package io.mosip.certify.services;

import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.dto.CredentialLedgerSearchRequest;
import io.mosip.certify.core.dto.CredentialStatusResponse;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.core.spi.CredentialLedgerService;
import io.mosip.certify.entity.Ledger;
import io.mosip.certify.core.dto.CredentialStatusDetail;
import io.mosip.certify.repository.LedgerRepository;
import io.mosip.certify.config.LedgerSearchProperties;
import io.mosip.certify.core.dto.LedgerSearchResult;
import io.mosip.certify.exception.ManagementApiException;
import org.springframework.http.HttpStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class CredentialLedgerServiceImpl implements CredentialLedgerService {
    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private LedgerSearchProperties searchProperties;

    @Autowired
    private IndexedAttributePolicy indexedAttributePolicy;

    @Override
    public LedgerSearchResult<CredentialStatusResponse> searchCredentialLedger(CredentialLedgerSearchRequest request) {
        return search(request, false);
    }

    @Override
    public LedgerSearchResult<CredentialStatusResponse> searchCredentialLedgerV2(CredentialLedgerSearchRequest request) {
        return search(request, true);
    }

    private LedgerSearchResult<CredentialStatusResponse> search(CredentialLedgerSearchRequest request, boolean isV2) {
        validateFilters(request);
        boolean paginated = request.isPaginationRequested();
        int limit = paginated ? (request.getLimit() != null ? request.getLimit() : searchProperties.getDefaultLimit())
                : searchProperties.getLegacyLimit();
        int offset = paginated && request.getOffset() != null ? request.getOffset() : 0;
        if (paginated) {
            validatePaging(limit, offset);
        }

        List<Ledger> rows;
        try {
            // One extra row tells whether more matches exist, without a count query.
            rows = ledgerRepository.findBySearchRequest(request, limit + 1, offset);
        } catch (Exception e) {
            throw new CertifyException(ErrorConstants.SEARCH_CREDENTIALS_FAILED, "Failed to search credentials.");
        }
        if (rows == null) {
            throw new CertifyException(ErrorConstants.SEARCH_CREDENTIALS_FAILED, "Failed to search credentials.");
        }

        boolean hasMore = rows.size() > limit;
        if (hasMore && !paginated) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.PAGINATION_REQUIRED,
                    "More than " + limit + " credentials match. Send limit and offset to read them page by page, "
                            + "or narrow the search, for example with document_id.");
        }
        List<Ledger> page = hasMore ? rows.subList(0, limit) : rows;
        return new LedgerSearchResult<>(mapRecordsToResponses(page, isV2), hasMore, paginated);
    }

    private void validateFilters(CredentialLedgerSearchRequest request) {
        boolean hasCredentialId = request.getCredentialId() != null && !request.getCredentialId().isBlank();
        Map<String, String> filters = request.getIndexedAttributesEquals();
        boolean hasFilters = filters != null && !filters.isEmpty();
        if (!hasCredentialId && !hasFilters) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.INVALID_SEARCH_CRITERIA,
                    "Provide credentialId or at least one exact-match entry in indexedAttributesEquals.");
        }
        String problem = indexedAttributePolicy.firstProblem(request.getCredentialType(), filters);
        if (problem != null) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.INVALID_SEARCH_CRITERIA, problem);
        }
    }

    private void validatePaging(int limit, int offset) {
        if (limit < 1 || limit > searchProperties.getMaxLimit()) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.INVALID_REQUEST,
                    "limit must be between 1 and " + searchProperties.getMaxLimit() + ".");
        }
        if (offset < 0) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.INVALID_REQUEST, "offset must not be negative.");
        }
        if (offset > searchProperties.getMaxOffset()) {
            throw new ManagementApiException(HttpStatus.BAD_REQUEST, ErrorConstants.SEARCH_WINDOW_EXCEEDED,
                    "offset must not exceed " + searchProperties.getMaxOffset()
                            + ". Narrow the search, for example by adding document_id.");
        }
    }

    private CredentialStatusResponse mapToSearchResponse(Ledger record, CredentialStatusDetail statusDetail) {
        CredentialStatusResponse response = new CredentialStatusResponse();
        response.setCredentialId(record.getCredentialId());
        response.setIssuerId(record.getIssuerId());
        response.setIssueDate(record.getIssuanceDate());
        response.setIssuanceDate(record.getIssuanceDate());
        response.setExpirationDate(record.getExpirationDate());
        response.setCredentialType(record.getCredentialType());
        if (statusDetail != null) {
            response.setStatusListCredentialUrl(statusDetail.getStatusListCredentialId());
            response.setStatusListIndex(statusDetail.getStatusListIndex());
            response.setStatusPurpose(statusDetail.getStatusPurpose());
            Long createdDtimes = statusDetail.getCreatedTimes();
            if (createdDtimes != null) {
                LocalDateTime ts = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(createdDtimes),
                        ZoneOffset.UTC);
                response.setStatusTimestamp(ts);
            }
        }
        return response;
    }

    @Transactional
    @Override
    public void storeLedgerEntry(String credentialId, String issuerId, String credentialType, CredentialStatusDetail statusDetails, Map<String, Object> indexedAttributes, LocalDateTime issuanceDate, LocalDateTime expirationDate) {
        try {
            Ledger ledger = new Ledger();
            if(credentialId != null) {
                ledger.setCredentialId(credentialId);
            }
            ledger.setIssuerId(issuerId);
            ledger.setIssuanceDate(issuanceDate);
            ledger.setExpirationDate(expirationDate);
            ledger.setCredentialType(credentialType);
            ledger.setIndexedAttributes(indexedAttributes);

            // Store status details as array
            List<CredentialStatusDetail> statusDetailsList = new ArrayList<>();
            if(statusDetails != null) {
                statusDetailsList.add(statusDetails);
            }
            ledger.setCredentialStatusDetails(statusDetailsList);

            ledgerRepository.save(ledger);
        } catch (Exception e) {
            log.error("Failed to record ledger entry.", e);
            throw new CertifyException(ErrorConstants.LEDGER_ENTRY_FAILED, "Failed to record ledger entry.");
        }
    }

    private List<CredentialStatusResponse> mapRecordsToResponses(List<Ledger> records, boolean isV2) {
        if (records.isEmpty()) {
            return Collections.emptyList();
        }
        return records.stream()
                .flatMap(record -> {
                    List<CredentialStatusDetail> details = record.getCredentialStatusDetails();
                    return Optional.ofNullable(details)
                            .filter(list -> !list.isEmpty())
                            .map(list -> list.stream().map(detail -> {
                                CredentialStatusResponse response = mapToSearchResponse(record, detail);
                                if (isV2) {
                                    response.setIssueDate(null);
                                } else {
                                    response.setIssuanceDate(null);
                                }
                                return response;
                            }))
                            .orElseGet(() -> {
                                CredentialStatusResponse response = mapToSearchResponse(record, null);
                                if (isV2) {
                                    response.setIssueDate(null);
                                } else {
                                    response.setIssuanceDate(null);
                                }
                                return Stream.of(response);
                            });
                })
                .collect(Collectors.toList());
    }

}
