package io.mosip.certify.erl;
import io.mosip.certify.dmt.DmtPersistenceAutoConfiguration;
import io.mosip.certify.erl.repository.ErlLicenceRepository;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class ErlMySqlIntegrationTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.32")
            .withDatabaseName("licence").withCommand("--log-bin-trust-function-creators=1").withInitScript("erl-fixtures.sql");
    @Test void nativeQueryUsesMySqlSemanticsAndConfiguredIndependentCharges() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                DmtPersistenceAutoConfiguration.class, ErlAutoConfiguration.class))
            .withPropertyValues("dmt.datasource.enabled=true", "dmt.erl.enabled=true",
                "dmt.datasource.url=" + mysql.getJdbcUrl(), "dmt.datasource.username=" + mysql.getUsername(),
                "dmt.datasource.password=" + mysql.getPassword(), "dmt.erl.licence-fee-code=FEE",
                "dmt.erl.arrears-code=ARR", "dmt.erl.fines-code=FINE")
            .run(context -> {
                assertThat(context).hasNotFailed();
                ErlLicenceRepository repository = context.getBean(ErlLicenceRepository.class);
                LocalDateTime timestamp = LocalDateTime.of(2026,10,16,14,30);
                var row = repository.findForIssuance("TEST", timestamp, "FEE", "ARR", "FINE").orElseThrow();
                assertThat(row.getLicenseNo()).isEqualTo("LATEST");
                assertThat(row.getOwnerName()).isEqualTo("Test owner සිංහල");
                assertThat(row.getOwnerAddress()).isEqualTo("First line, Colombo");
                assertThat(row.getLicenceFee()).isEqualByComparingTo("12345.67");
                assertThat(row.getArrears()).isEqualByComparingTo("25.50");
                assertThat(row.getFinesPaid()).isEqualByComparingTo("11.25");
                assertThat(row.getVehicleWeight()).isEqualByComparingTo("1234.56");
                assertThat(row.getLicenceId()).isEqualTo(new java.math.BigInteger("2"));
                assertThat(row.getIssuedDate()).isEqualTo(timestamp);
                assertThat(repository.findForIssuance("TEST", timestamp.plusSeconds(1), "FEE", "ARR", "FINE").orElseThrow().getLicenseNo()).isEqualTo("OTHER-TIME");
                assertThat(repository.findForIssuance("TEST", timestamp.plusSeconds(2), "FEE", "ARR", "FINE")).isEmpty();
                assertThat(repository.findForIssuance("' OR 1=1 --", timestamp, "FEE", "ARR", "FINE")).isEmpty();
                var nulls = repository.findForIssuance("NULL-TEST", timestamp, "FEE", "ARR", "FINE").orElseThrow();
                assertThat(nulls.getFinesPaid()).isNull();
                assertThat(nulls.getOwnerName()).isNull();
                assertThat(nulls.getOwnerAddress()).isNull();
            });
    }
}
