package io.mosip.certify.services;

import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.entity.StatusListAllocation;
import io.mosip.certify.entity.StatusListCredential;
import io.mosip.certify.repository.StatusListAllocationRepository;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Chooses the active status list for an issuer and purpose, reserves one position in it, and creates the
 * first list or a replacement when needed.
 * <p>
 * Reservation commits in its own transaction (see {@link DatabaseStatusListIndexProvider}) before the
 * credential is signed, so a committed position is never handed to another credential. Creation is
 * serialised per issuer and purpose with a transaction-scoped advisory lock, and the active state is
 * re-read while holding it.
 */
@Slf4j
@Service
public class StatusListAllocationService {

    private static final int MAX_ATTEMPTS = 5;

    @Autowired
    private StatusListAllocationRepository allocationRepository;

    @Autowired
    private DatabaseStatusListIndexProvider indexProvider;

    @Autowired
    private StatusListCredentialService statusListCredentialService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${mosip.certify.data-provider-plugin.did-url}")
    private String issuerId;

    @Value("${mosip.certify.statuslist.usable-capacity-percentage:50}")
    private int usableCapacityPercentage;

    @Value("#{${mosip.certify.statuslist.size-in-kb:16}}")
    private long statusListSizeInKB;

    @PostConstruct
    void validateConfiguration() {
        if (usableCapacityPercentage < 1 || usableCapacityPercentage > 100) {
            throw new IllegalStateException("mosip.certify.statuslist.usable-capacity-percentage must be between 1 and 100");
        }
        if (statusListSizeInKB < 1) {
            throw new IllegalStateException("mosip.certify.statuslist.size-in-kb must be positive");
        }
        physicalCapacity(statusListSizeInKB); // fails on overflow
    }

    /**
     * Reserves one position for the given purpose.
     *
     * @throws CertifyException with {@code STATUS_LIST_INDEX_UNAVAILABLE} when no position could be reserved
     */
    public AllocatedPosition allocate(String statusPurpose) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Optional<StatusListAllocation> active = allocationRepository
                    .findFirstByIssuerIdAndStatusPurposeAndIsActiveTrue(issuerId, statusPurpose);
            if (active.isEmpty()) {
                ensureActiveList(statusPurpose);
                continue;
            }
            String listId = active.get().getStatusListCredentialId();
            Optional<IndexClaim> claim = indexProvider.acquireIndex(listId, Map.of());
            if (claim.isEmpty()) {
                // The list closed while this request was claiming. Look up the current active list.
                continue;
            }
            if (claim.get().listClosed()) {
                try {
                    ensureActiveList(statusPurpose);
                } catch (RuntimeException e) {
                    // The closed list cannot be over-allocated; the next request creates the replacement.
                    log.warn("Could not create the replacement status list now; the next request will retry", e);
                }
            }
            return new AllocatedPosition(listId, claim.get().index());
        }
        throw new CertifyException(ErrorConstants.STATUS_LIST_INDEX_UNAVAILABLE,
                "Error fetching available index from status list");
    }

    /**
     * Makes sure exactly one list is active for the purpose. Safe to call concurrently: the second caller
     * waits for the lock, re-reads the state and finds the list the first caller created.
     */
    void ensureActiveList(String statusPurpose) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.executeWithoutResult(status -> {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:key))")
                    .setParameter("key", issuerId + "|" + statusPurpose)
                    .getResultList();

            if (allocationRepository.findFirstByIssuerIdAndStatusPurposeAndIsActiveTrue(issuerId, statusPurpose).isPresent()) {
                return;
            }
            StatusListCredential list = statusListCredentialService.generateStatusListCredential(statusPurpose);
            long physical = physicalCapacity(list.getCapacityInKB());

            StatusListAllocation allocation = new StatusListAllocation();
            allocation.setStatusListCredentialId(list.getId());
            allocation.setIssuerId(issuerId);
            allocation.setStatusPurpose(statusPurpose);
            allocation.setPhysicalCapacity(physical);
            allocation.setAllocationLimit(Math.multiplyExact(physical, (long) usableCapacityPercentage) / 100);
            allocation.setAllocatedCount(0L);
            allocation.setIsActive(true);
            allocation.setUpdatedDtimes(LocalDateTime.now(java.time.ZoneOffset.UTC));
            allocationRepository.saveAndFlush(allocation);

            // Keep the older "credential_status" flag in step with the allocation state.
            entityManager.createNativeQuery("""
                    UPDATE status_list_credential s
                    SET credential_status = CAST('FULL' AS credential_status_enum)
                    FROM status_list_allocation a
                    WHERE a.status_list_credential_id = s.id
                        AND NOT a.is_active
                        AND a.issuer_id = :issuer
                        AND a.status_purpose = :purpose
                        AND s.credential_status <> CAST('FULL' AS credential_status_enum)
                    """)
                    .setParameter("issuer", issuerId)
                    .setParameter("purpose", statusPurpose)
                    .executeUpdate();
            log.info("Created status list {} for purpose {} ({} positions, limit {})",
                    list.getId(), statusPurpose, physical, allocation.getAllocationLimit());
        });
    }

    private static long physicalCapacity(long sizeInKB) {
        try {
            return Math.multiplyExact(Math.multiplyExact(sizeInKB, 1024L), 8L);
        } catch (ArithmeticException e) {
            throw new IllegalStateException("Status list size is too large: " + sizeInKB + " KB", e);
        }
    }
}
