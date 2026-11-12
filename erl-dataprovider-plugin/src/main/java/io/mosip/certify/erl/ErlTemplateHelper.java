package io.mosip.certify.erl;
import java.time.*;
import java.util.Map;
import java.util.List;
import org.json.JSONObject;
import io.mosip.certify.api.spi.CredentialTemplateCustomizer;
import io.mosip.certify.api.exception.DataProviderExchangeException;

public class ErlTemplateHelper implements CredentialTemplateCustomizer {
    private final Clock clock;
    public ErlTemplateHelper(Clock clock) { this.clock = clock; }
    public Instant expiration(LocalDate validTo) throws DataProviderExchangeException {
        if (validTo == null) throw new DataProviderExchangeException("erl_missing_validity");
        Instant expiration = validTo.plusDays(1).atStartOfDay(ZoneId.of("Asia/Colombo")).toInstant();
        if (!expiration.isAfter(clock.instant())) throw new DataProviderExchangeException("erl_licence_expired");
        return expiration;
    }
    @Override
    public void customize(Map<String, Object> parameters) throws DataProviderExchangeException {
        // The licence must be revocable: it needs its own id and the status list entry allocated for it.
        Object credentialId = parameters.get("credentialId");
        if (credentialId == null || credentialId.toString().isBlank()) throw new DataProviderExchangeException("erl_missing_credential_id");
        if (!(parameters.get("credentialStatus") instanceof Map<?, ?> status) || status.isEmpty())
            throw new DataProviderExchangeException("erl_missing_credential_status");
        Object validTo = parameters.get("validity_period_end_date");
        Instant expiry = expiration(validTo == null ? null : LocalDate.parse(validTo.toString()));
        parameters.put("validUntil", expiry.toString());
        parameters.put("expirationDate", expiry.toString());
        JSONObject subject = new JSONObject();
        subject.put("id", parameters.get("_holderId"));
        for (String field : List.of("licence_id", "license_no", "class_of_vehicle", "fuel_type", "vehicle_no",
                "owner_name", "owner_address", "vehicle_weight", "licence_fee", "arrears", "fines_paid",
                "validity_period_start_date", "validity_period_end_date", "issued_date")) {
            Object value = parameters.get(field);
            subject.put(field, value == null ? JSONObject.NULL : value);
        }
        parameters.put("erlCredentialSubject", subject.toString());
        parameters.put("erlCredentialStatus", new JSONObject((Map<?, ?>) parameters.get("credentialStatus")).toString());
    }
}
