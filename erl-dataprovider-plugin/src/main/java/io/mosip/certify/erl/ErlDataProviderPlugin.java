package io.mosip.certify.erl;

import io.mosip.certify.api.spi.DataProviderPlugin;
import io.mosip.certify.api.exception.DataProviderExchangeException;
import io.mosip.certify.erl.repository.*;
import org.json.JSONObject;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

public class ErlDataProviderPlugin implements DataProviderPlugin {
    private final ErlLicenceRepository repository;
    private final ErlProperties properties;
    private final ErlTemplateHelper helper;
    public ErlDataProviderPlugin(ErlLicenceRepository repository, ErlProperties properties, ErlTemplateHelper helper) {
        this.repository = repository; this.properties = properties; this.helper = helper;
    }
    @Override
    @Transactional(transactionManager = "dmtTransactionManager", readOnly = true, timeout = 5)
    public JSONObject fetchData(Map<String, Object> claims) throws DataProviderExchangeException {
        ErlLookupRequest lookup = ErlLookupRequest.parse(claims);
        try {
            ErlLicenceProjection row = repository.findForIssuance(lookup.vehicleNumber(), lookup.issuedDate(),
                    properties.getLicenceFeeCode(), properties.getArrearsCode(), properties.getFinesCode())
                    .orElseThrow(() -> new DataProviderExchangeException("erl_licence_not_found"));
            helper.expiration(row.getValidityPeriodEndDate());
            JSONObject result = new JSONObject();
            put(result, "licence_id", row.getLicenceId() == null ? null : row.getLicenceId().toString());
            put(result, "license_no", row.getLicenseNo());
            put(result, "class_of_vehicle", row.getClassOfVehicle());
            put(result, "fuel_type", row.getFuelType());
            put(result, "vehicle_no", row.getVehicleNo());
            put(result, "owner_name", row.getOwnerName());
            put(result, "owner_address", row.getOwnerAddress());
            put(result, "vehicle_weight", row.getVehicleWeight());
            put(result, "licence_fee", row.getLicenceFee());
            put(result, "arrears", row.getArrears());
            put(result, "fines_paid", row.getFinesPaid());
            put(result, "validity_period_start_date", row.getValidityPeriodStartDate());
            put(result, "validity_period_end_date", row.getValidityPeriodEndDate());
            put(result, "issued_date", row.getIssuedDate());
            return result;
        } catch (DataAccessException e) {
            // Do not propagate SQL, bind values, database credentials or personal information.
            throw new DataProviderExchangeException("erl_datasource_unavailable");
        }
    }
    private static void put(JSONObject result, String field, Object value) {
        result.put(field, value == null ? JSONObject.NULL : value instanceof java.time.LocalDateTime timestamp
                ? timestamp.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd\'T\'HH:mm:ss"))
                : value instanceof java.time.temporal.TemporalAccessor ? value.toString() : value);
    }
}
