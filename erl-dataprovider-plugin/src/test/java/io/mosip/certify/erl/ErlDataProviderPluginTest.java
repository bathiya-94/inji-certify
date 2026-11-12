package io.mosip.certify.erl;
import io.mosip.certify.api.exception.*;
import io.mosip.certify.erl.repository.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ErlDataProviderPluginTest {
    private static final Map<String,Object> LOOKUP = Map.of("vehicle_number", "TEST", "issued_date", "2026-10-16T14:30:00");
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC);
    @Test void validatesExactFlatLookup() {
        assertThat(ErlLookupRequest.parse(LOOKUP).issuedDate()).isEqualTo(LocalDateTime.of(2026,10,16,14,30));
        for (Object date : List.of("2026-02-30T14:30:00", "2026-10-16T14:30:00Z", "2026-10-16T14:30:00.0", "2026-10-16", 123)) {
            assertThatThrownBy(() -> ErlLookupRequest.parse(Map.of("vehicle_number", "TEST", "issued_date", date)))
                .isInstanceOf(LookupValidationException.class);
        }
        for (String vehicle : List.of("", " ", "x\n", "x".repeat(51)))
            assertThatThrownBy(() -> ErlLookupRequest.parse(Map.of("vehicle_number", vehicle, "issued_date", LOOKUP.get("issued_date"))))
                .isInstanceOf(LookupValidationException.class);
        assertThatThrownBy(() -> ErlLookupRequest.parse(Map.of("provider", "erl"))).isInstanceOf(LookupValidationException.class);
    }
    @Test void mapsPreciseValuesNullsAndConfiguredCharges() throws Exception {
        ErlLicenceRepository repository = mock(ErlLicenceRepository.class);
        ErlLicenceProjection row = mock(ErlLicenceProjection.class);
        when(row.getLicenceId()).thenReturn(new BigInteger("18446744073709551614"));
        when(row.getOwnerName()).thenReturn("Unicode \"owner\" සිංහල");
        when(row.getLicenceFee()).thenReturn(new BigDecimal("12345.67"));
        when(row.getValidityPeriodEndDate()).thenReturn(LocalDate.of(2026,12,31));
        when(repository.findForIssuance("TEST", LocalDateTime.of(2026,10,16,14,30), "FEE", "ARR", "FINE"))
            .thenReturn(Optional.of(row));
        ErlProperties properties = new ErlProperties();
        properties.setLicenceFeeCode("FEE"); properties.setArrearsCode("ARR"); properties.setFinesCode("FINE");
        JSONObject result = new ErlDataProviderPlugin(repository, properties, new ErlTemplateHelper(clock)).fetchData(LOOKUP);
        assertThat(result.getString("licence_id")).isEqualTo("18446744073709551614");
        assertThat(result.getBigDecimal("licence_fee")).isEqualByComparingTo("12345.67");
        assertThat(result.has("fines_paid")).isTrue();
        assertThat(result.isNull("fines_paid")).isTrue();
        assertThat(result.length()).isEqualTo(14);
        Map<String,Object> parameters = new HashMap<>(result.toMap());
        parameters.put("_holderId", "did:jwk:holder");
        parameters.put("validUntil", "2099-01-01T00:00:00Z");
        parameters.put("credentialId", "urn:uuid:1111");
        parameters.put("credentialStatus", Map.of("id", "https://c/status-list/L#7", "type", "BitstringStatusListEntry",
            "statusPurpose", "revocation", "statusListIndex", "7", "statusListCredential", "https://c/status-list/L"));
        new ErlTemplateHelper(clock).customize(parameters);
        JSONObject status = new JSONObject((String) parameters.get("erlCredentialStatus"));
        assertThat(status.getString("statusListIndex")).isEqualTo("7");
        assertThat(status.getString("statusPurpose")).isEqualTo("revocation");
        assertThat(status.getString("statusListCredential")).isEqualTo("https://c/status-list/L");
        assertThat(parameters.get("validUntil")).isEqualTo("2026-12-31T18:30:00Z");
        JSONObject subject = new JSONObject((String) parameters.get("erlCredentialSubject"));
        assertThat(subject.getString("owner_name")).isEqualTo(row.getOwnerName());
        assertThat(subject.has("fines_paid") && subject.isNull("fines_paid")).isTrue();
    }
    @Test void refusesToIssueAnUnrevocableLicence() {
        Map<String,Object> valid = new HashMap<>(Map.of("validity_period_end_date", "2026-12-31", "_holderId", "did:jwk:h",
            "credentialId", "urn:uuid:1", "credentialStatus", Map.of("statusListIndex", "1")));
        for (String missing : List.of("credentialId", "credentialStatus")) {
            Map<String,Object> parameters = new HashMap<>(valid);
            parameters.remove(missing);
            assertThatThrownBy(() -> new ErlTemplateHelper(clock).customize(parameters))
                .isInstanceOf(DataProviderExchangeException.class);
        }
        Map<String,Object> blankId = new HashMap<>(valid);
        blankId.put("credentialId", " ");
        assertThatThrownBy(() -> new ErlTemplateHelper(clock).customize(blankId)).isInstanceOf(DataProviderExchangeException.class);
        Map<String,Object> emptyStatus = new HashMap<>(valid);
        emptyStatus.put("credentialStatus", Map.of());
        assertThatThrownBy(() -> new ErlTemplateHelper(clock).customize(emptyStatus)).isInstanceOf(DataProviderExchangeException.class);
    }
    @Test void enforcesExpiryAtColomboMidnight() throws Exception {
        LocalDate validTo = LocalDate.of(2026,10,19);
        Instant boundary = Instant.parse("2026-10-19T18:30:00Z");
        assertThat(new ErlTemplateHelper(Clock.fixed(boundary.minusSeconds(1), ZoneOffset.UTC)).expiration(validTo)).isEqualTo(boundary);
        for (Instant now : List.of(boundary, boundary.plusSeconds(1)))
            assertThatThrownBy(() -> new ErlTemplateHelper(Clock.fixed(now, ZoneOffset.UTC)).expiration(validTo))
                .isInstanceOf(DataProviderExchangeException.class);
        assertThatThrownBy(() -> new ErlTemplateHelper(clock).expiration(null)).isInstanceOf(DataProviderExchangeException.class);
    }
    @Test void sanitizesDatabaseFailuresAndNeverQueriesInvalidInput() {
        ErlLicenceRepository repository = mock(ErlLicenceRepository.class);
        ErlProperties properties = new ErlProperties();
        ErlDataProviderPlugin plugin = new ErlDataProviderPlugin(repository, properties, new ErlTemplateHelper(clock));
        assertThatThrownBy(() -> plugin.fetchData(Map.of())).isInstanceOf(LookupValidationException.class);
        verifyNoInteractions(repository);
        when(repository.findForIssuance(any(), any(), any(), any(), any()))
            .thenThrow(new DataAccessResourceFailureException("secret SQL and credentials"));
        assertThatThrownBy(() -> plugin.fetchData(LOOKUP)).hasMessage("erl_datasource_unavailable");
    }
}
