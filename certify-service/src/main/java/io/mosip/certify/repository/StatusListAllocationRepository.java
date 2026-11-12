package io.mosip.certify.repository;

import io.mosip.certify.entity.StatusListAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StatusListAllocationRepository extends JpaRepository<StatusListAllocation, String> {

    /** The single list that currently accepts allocations for an issuer and purpose, if any. */
    Optional<StatusListAllocation> findFirstByIssuerIdAndStatusPurposeAndIsActiveTrue(String issuerId, String statusPurpose);
}
