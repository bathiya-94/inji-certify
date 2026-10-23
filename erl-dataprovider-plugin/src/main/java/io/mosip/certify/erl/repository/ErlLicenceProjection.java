package io.mosip.certify.erl.repository;
import java.math.*;
import java.time.*;
public interface ErlLicenceProjection {
    BigInteger getLicenceId();
    String getLicenseNo();
    String getClassOfVehicle();
    String getFuelType();
    String getVehicleNo();
    String getOwnerName();
    String getOwnerAddress();
    BigDecimal getVehicleWeight();
    BigDecimal getLicenceFee();
    BigDecimal getArrears();
    BigDecimal getFinesPaid();
    LocalDate getValidityPeriodStartDate();
    LocalDate getValidityPeriodEndDate();
    LocalDateTime getIssuedDate();
}
