package io.mosip.certify.repository;

import io.mosip.certify.entity.StatusListCredential;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface StatusListCredentialRepository extends JpaRepository<StatusListCredential, String> {

    /**
     * Find a suitable status list credential that is available (not full) and matches the given purpose
     *
     * @param statusPurpose The purpose of the status list (e.g., "revocation", "suspension")
     * @return An optional containing the first available status list credential, or empty if none found
     */
    Optional<StatusListCredential> findFirstByStatusPurposeAndCredentialStatusOrderByCreatedDtimesDesc(
            String statusPurpose,
            StatusListCredential.CredentialStatus credentialStatus
    );

    /**
     * Find capacity of status list by ID
     */
    @Query("SELECT s.capacityInKB FROM StatusListCredential s WHERE s.id = :id")
    Optional<Long> findCapacityInKBById(@Param("id") String id);

    /**
     * Find the maximum updated timestamp from all status list credentials
     */
    @Query("SELECT MAX(s.updatedDtimes) FROM StatusListCredential s WHERE s.updatedDtimes IS NOT NULL")
    Optional<LocalDateTime> findMaxUpdatedTime();

    /**
     * Locks one list for publication. Empty when another node holds the lock (or the list does not exist),
     * so two nodes never publish the same list at the same time.
     */
    @Query(value = "SELECT * FROM status_list_credential WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<StatusListCredential> lockForPublication(@Param("id") String id);
}
