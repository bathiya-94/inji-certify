package io.mosip.certify.erl.repository;
import io.mosip.certify.erl.entity.ErlLicence;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.Optional;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
public interface ErlLicenceRepository extends Repository<ErlLicence, BigInteger> {
    @Query(value = ErlSql.FIND_FOR_ISSUANCE, nativeQuery = true)
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    Optional<ErlLicenceProjection> findForIssuance(
        @Param("vehicleNumber") String vehicleNumber, @Param("issuedDate") LocalDateTime issuedDate,
        @Param("licenceFeeCode") String licenceFeeCode, @Param("arrearsCode") String arrearsCode,
        @Param("finesCode") String finesCode);
}
