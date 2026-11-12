package io.mosip.certify.repository;

import io.mosip.certify.entity.CredentialStatusTransaction;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CredentialStatusTransactionRepository extends JpaRepository<CredentialStatusTransaction, Long> {

    /**
     * Find a batch of unprocessed transactions, ordered by creation time, with custom batch size.
     */
    List<CredentialStatusTransaction> findByIsProcessedFalseOrderByCreatedDtimesAsc(Pageable pageable);

    /**
     * Records a true revocation unless that position already has one. Safe under concurrent callers: the unique
     * partial index {@code uq_cst_true_revocation} decides, and the loser inserts nothing.
     *
     * @return 1 when a row was inserted, 0 when the position was already revoked
     */
    @Modifying
    @Query(value = "INSERT INTO credential_status_transaction (credential_id, status_purpose, status_value, "
            + "status_list_credential_id, status_list_index, cr_dtimes, is_processed) "
            + "VALUES (:credentialId, 'revocation', TRUE, :listId, :index, :createdAt, FALSE) "
            + "ON CONFLICT (status_list_credential_id, status_list_index, status_purpose) "
            + "WHERE status_value = TRUE AND status_purpose = 'revocation' DO NOTHING", nativeQuery = true)
    int insertRevocationIfAbsent(@Param("credentialId") String credentialId, @Param("listId") String listId,
                                 @Param("index") long index, @Param("createdAt") LocalDateTime createdAt);

    /** The existing true revocation of a position, if any. */
    Optional<CredentialStatusTransaction> findFirstByStatusListCredentialIdAndStatusListIndexAndStatusPurposeAndStatusValueTrueOrderByTransactionLogIdAsc(
            String statusListCredentialId, Long statusListIndex, String statusPurpose);

    /** Pending work of one list in the order it was requested. */
    List<CredentialStatusTransaction> findByStatusListCredentialIdAndIsProcessedFalseOrderByCreatedDtimesAscTransactionLogIdAsc(
            String statusListCredentialId, Pageable pageable);

    /** Lists that have unpublished changes, the list waiting longest first. */
    @Query(value = "SELECT status_list_credential_id FROM credential_status_transaction "
            + "WHERE NOT is_processed AND status_list_credential_id IS NOT NULL "
            + "GROUP BY status_list_credential_id ORDER BY MIN(cr_dtimes) LIMIT :limit", nativeQuery = true)
    List<String> findListsWithPendingWork(@Param("limit") int limit);

    /** When the oldest unpublished change of a list was requested. */
    @Query("SELECT MIN(t.createdDtimes) FROM CredentialStatusTransaction t "
            + "WHERE t.isProcessed = false AND t.statusListCredentialId = :listId")
    Optional<LocalDateTime> findOldestPendingTime(@Param("listId") String listId);

    /** When the oldest unpublished change of any list was requested. */
    @Query("SELECT MIN(t.createdDtimes) FROM CredentialStatusTransaction t WHERE t.isProcessed = false")
    Optional<LocalDateTime> findOldestPendingTime();

    long countByIsProcessedFalse();
}
