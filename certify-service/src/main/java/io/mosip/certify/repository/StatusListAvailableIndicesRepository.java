package io.mosip.certify.repository;

import io.mosip.certify.entity.StatusListAvailableIndices;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StatusListAvailableIndicesRepository extends JpaRepository<StatusListAvailableIndices, Long> {

    /**
     * Count assigned indices for a specific status list
     */
    long countByStatusListCredentialIdAndIsAssignedTrue(String statusListCredentialId);

    /** One position of a list. */
    Optional<StatusListAvailableIndices> findByStatusListCredentialIdAndListIndex(String statusListCredentialId, Long listIndex);

    /**
     * Confirms that a reserved position now belongs to an issued credential. Updates nothing when the position
     * was not reserved or is already confirmed.
     *
     * @return the number of rows updated (1 on success)
     */
    @Modifying
    @Query("UPDATE StatusListAvailableIndices s SET s.issuedAt = :issuedAt, s.credentialId = :credentialId "
            + "WHERE s.statusListCredentialId = :listId AND s.listIndex = :index AND s.isAssigned = true AND s.issuedAt IS NULL")
    int confirmIssued(@Param("listId") String listId, @Param("index") long index,
                      @Param("credentialId") String credentialId, @Param("issuedAt") LocalDateTime issuedAt);
}