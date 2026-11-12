package io.mosip.certify.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One row per status list. Tracks committed allocations against the list's limit and whether the
 * list still accepts new allocations.
 */
@Entity
@Table(name = "status_list_allocation")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatusListAllocation {

    @Id
    @Column(name = "status_list_credential_id", length = 255)
    private String statusListCredentialId;

    @Column(name = "issuer_id", length = 255, nullable = false)
    private String issuerId;

    @Column(name = "status_purpose", length = 100, nullable = false)
    private String statusPurpose;

    @Column(name = "physical_capacity", nullable = false)
    private Long physicalCapacity;

    @Column(name = "allocation_limit", nullable = false)
    private Long allocationLimit;

    @Column(name = "allocated_count", nullable = false)
    private Long allocatedCount = 0L;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = Boolean.TRUE;

    @Column(name = "cr_dtimes", nullable = false, updatable = false)
    private LocalDateTime createdDtimes;

    @Column(name = "upd_dtimes")
    private LocalDateTime updatedDtimes;

    @PrePersist
    protected void onCreate() {
        createdDtimes = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }
}
