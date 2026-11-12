package io.mosip.certify.repository;

import io.mosip.certify.core.dto.CredentialLedgerSearchRequest;
import io.mosip.certify.entity.Ledger;

import java.util.List;

public interface LedgerIssuanceTableCustomRepository {
    /**
     * Finds ledger rows matching every supplied filter (AND), newest first by internal row id.
     *
     * @param fetchLimit the most rows to read; callers ask for one more than the page size to learn whether more exist
     * @param offset     the number of matching rows to skip
     */
    List<Ledger> findBySearchRequest(CredentialLedgerSearchRequest request, int fetchLimit, int offset);
}
