package io.mosip.certify.services;

import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.entity.StatusListAllocation;
import io.mosip.certify.repository.StatusListAllocationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Claims one random free position of a status list and counts it against the list's allocation limit in
 * the same transaction. The position row lock ({@code FOR UPDATE SKIP LOCKED}) prevents two requests
 * from taking the same position; the conditional counter update enforces the limit exactly.
 */
@Slf4j
@Service
public class DatabaseStatusListIndexProvider implements StatusListIndexProvider {

    @Autowired
    private StatusListAllocationRepository allocationRepository;

    @Autowired
    private EntityManager entityManager;

    @Override
    public String getProviderName() {
        return "DatabaseRandomAvailableIndexProvider";
    }

    /**
     * Runs in its own transaction so that a rejected counter update rolls back the claimed position
     * without affecting the caller.
     *
     * @return the claim, or empty when the list is closed or exhausted. Any other failure is thrown.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public Optional<IndexClaim> acquireIndex(String listId, Map<String, Object> options) {
        Long claimedIndex = claimRandomPosition(listId);

        if (claimedIndex == null) {
            // Nothing was claimed. Only the allocation state can say whether the list is really full.
            StatusListAllocation allocation = allocationRepository.findById(listId)
                    .orElseThrow(() -> new CertifyException(ErrorConstants.STATUS_LIST_INDEX_UNAVAILABLE,
                            "No allocation record for status list " + listId));
            if (!Boolean.TRUE.equals(allocation.getIsActive())
                    || allocation.getAllocatedCount() >= allocation.getAllocationLimit()) {
                log.info("Status list {} is closed ({}/{})", listId, allocation.getAllocatedCount(), allocation.getAllocationLimit());
                return Optional.empty();
            }
            // Capacity remains but every free position is locked by other requests: a retryable condition.
            throw new CertifyException(ErrorConstants.STATUS_LIST_INDEX_UNAVAILABLE,
                    "No free position could be claimed in status list " + listId + ", please retry");
        }

        Object[] counter = incrementAllocation(listId);
        if (counter == null) {
            // Another request took the last allocation first. Undo this request's position claim.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.info("Status list {} closed while claiming position {}; claim rolled back", listId, claimedIndex);
            return Optional.empty();
        }
        boolean stillActive = (Boolean) counter[2];
        log.debug("Claimed position {} in status list {} ({} of {})", claimedIndex, listId, counter[0], counter[1]);
        return Optional.of(new IndexClaim(claimedIndex, !stillActive));
    }

    /** Claims a random unassigned position using the database lock-skipping mechanism. */
    private Long claimRandomPosition(String listId) {
        String sql = """
                WITH available_slot AS (
                    SELECT list_index
                    FROM status_list_available_indices
                    WHERE status_list_credential_id = :listId
                        AND is_assigned = false
                    ORDER BY RANDOM()
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE status_list_available_indices sla
                SET is_assigned = true,
                    upd_dtimes = NOW()
                FROM available_slot avs
                WHERE sla.status_list_credential_id = :listId
                    AND sla.list_index = avs.list_index
                    AND sla.is_assigned = false
                RETURNING sla.list_index
                """;
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("listId", listId);
        List<?> rows = query.getResultList();
        return rows.isEmpty() ? null : ((Number) rows.get(0)).longValue();
    }

    /**
     * Counts one allocation, only while the list is active and below its stored limit. Closes the list
     * in the same statement when this allocation reaches the limit.
     *
     * @return {allocated_count, allocation_limit, is_active}, or null when the list is not accepting allocations
     */
    private Object[] incrementAllocation(String listId) {
        String sql = """
                UPDATE status_list_allocation
                SET allocated_count = allocated_count + 1,
                    is_active = (allocated_count + 1 < allocation_limit),
                    upd_dtimes = NOW()
                WHERE status_list_credential_id = :listId
                    AND is_active
                    AND allocated_count < allocation_limit
                RETURNING allocated_count, allocation_limit, is_active
                """;
        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("listId", listId);
        List<?> rows = query.getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = (Object[]) rows.get(0);
        return new Object[]{((Number) row[0]).longValue(), ((Number) row[1]).longValue(), row[2]};
    }
}
