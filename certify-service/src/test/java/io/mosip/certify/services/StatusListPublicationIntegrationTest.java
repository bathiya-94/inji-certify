package io.mosip.certify.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.certify.config.StatusListPublicationProperties;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.repository.CredentialStatusTransactionRepository;
import io.mosip.certify.testsupport.PostgresTestSupport;
import io.mosip.certify.utils.BitStringStatusListUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** Publication of revocations on a real PostgreSQL: locking, rollback, ordering, idempotency and draining. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({StatusListPublicationService.class, StatusListUpdateBatchJob.class, StatusListPublicationProperties.class})
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=none", "mosip.certify.batch.status-list-update.batch-size=2"})
class StatusListPublicationIntegrationTest {

    private static final long CAPACITY_KB = 16; // the smallest list the bit utilities support: 131072 positions

    @TestConfiguration
    static class JsonConfig {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=certify");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
    }

    @BeforeAll
    static void schema() throws Exception {
        assumeTrue(PostgresTestSupport.ddlAvailable(), "db_scripts DDL not found from the module directory");
        PostgresTestSupport.createSchema(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StatusListPublicationService publication;
    @Autowired
    private StatusListUpdateBatchJob job;
    @Autowired
    private CredentialStatusTransactionRepository transactions;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockBean
    private StatusListCredentialService statusListCredentialService;

    private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM credential_status_transaction");
        jdbc.execute("DELETE FROM status_list_credential");
        ReflectionTestUtils.setField(publication, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        // "Signing" marks the document so the test can tell it was re-signed.
        when(statusListCredentialService.resignStatusListCredential(anyString())).thenAnswer(invocation ->
                new JSONObject((String) invocation.getArgument(0)).put("proof", new JSONObject().put("type", "test")).toString());
    }

    // ---- helpers -------------------------------------------------------------------------------

    private void list(String id) {
        JSONObject document = new JSONObject().put("id", id).put("validFrom", "2020-01-01T00:00:00Z")
                .put("credentialSubject", new JSONObject().put("type", "BitstringStatusList").put("statusPurpose", "revocation")
                        .put("encodedList", BitStringStatusListUtils.createEmptyEncodedList(CAPACITY_KB)));
        jdbc.update("INSERT INTO status_list_credential (id, vc_document, credential_type, status_purpose, capacity_in_kb, credential_status) "
                + "VALUES (?, ?, 'BitstringStatusListCredential', 'revocation', ?, CAST('AVAILABLE' AS credential_status_enum))",
                id, document.toString(), CAPACITY_KB);
    }

    private long pending(String listId, long index, String purpose, boolean value, String createdAt) {
        return jdbc.queryForObject("INSERT INTO credential_status_transaction (credential_id, status_purpose, status_value, "
                + "status_list_credential_id, status_list_index, cr_dtimes) VALUES ('c', ?, ?, ?, ?, CAST(? AS timestamp)) RETURNING transaction_log_id",
                Long.class, purpose, value, listId, index, createdAt);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private JSONObject document(String listId) {
        return new JSONObject(jdbc.queryForObject("SELECT vc_document FROM status_list_credential WHERE id = ?", String.class, listId));
    }

    private boolean bit(String listId, int index) throws Exception {
        String encoded = document(listId).getJSONObject("credentialSubject").getString("encodedList");
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(Base64.getUrlDecoder().decode(encoded.substring(1))))) {
            byte[] bytes = gzip.readAllBytes();
            return ((bytes[index / 8] >> (7 - index % 8)) & 1) == 1;
        }
    }

    // ---- publication -----------------------------------------------------------------------------

    @Test
    void publishesTheRevocation_setsOnlyThatBit_andMarksOnlyTheIncludedChangesProcessed() throws Exception {
        list("L1");
        pending("L1", 100, "revocation", true, "2026-11-09 09:59:00");

        assertEquals(1, publication.publish("L1", 10));

        assertTrue(bit("L1", 100));
        assertFalse(bit("L1", 99));
        assertFalse(bit("L1", 101));
        assertEquals(0, count("SELECT count(*) FROM credential_status_transaction WHERE NOT is_processed"));
        JSONObject published = document("L1");
        assertEquals("test", published.getJSONObject("proof").getString("type"));
        assertEquals(20000L, published.getJSONObject("credentialSubject").getLong("ttl"));
        // The signer reads exactly yyyy-MM-dd'T'HH:mm:ss.SSS'Z'; microseconds or a missing fraction break it.
        assertEquals("2026-11-09T10:00:00.000Z", published.getString("validFrom"));
        assertDoesNotThrow(() -> LocalDateTime.parse(published.getString("validFrom"),
                java.time.format.DateTimeFormatter.ofPattern(io.mosip.certify.core.constants.Constants.UTC_DATETIME_PATTERN)));
        assertEquals(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), jdbc.queryForObject(
                "SELECT processed_dtimes FROM credential_status_transaction", LocalDateTime.class));
    }

    @Test
    void batchSizeLimitsWhatIsPublished_andTheRestStaysPending() throws Exception {
        list("L1");
        for (int i = 0; i < 5; i++) pending("L1", 10 + i, "revocation", true, "2026-11-09 09:59:0" + i);

        assertEquals(2, publication.publish("L1", 2));

        assertEquals(2, count("SELECT count(*) FROM credential_status_transaction WHERE is_processed"));
        assertTrue(bit("L1", 10) && bit("L1", 11));
        assertFalse(bit("L1", 12));
        assertEquals(3, count("SELECT count(*) FROM credential_status_transaction WHERE NOT is_processed"));
    }

    @Test
    void nothingPending_isANoOp() {
        list("L1");
        assertEquals(0, publication.publish("L1", 10));
        assertFalse(document("L1").has("proof"));
    }

    @Test
    void laterRequestForAPositionWins_forPurposesThatCanBeUndone() throws Exception {
        list("L1");
        pending("L1", 7, "suspension", true, "2026-11-09 09:59:00");
        pending("L1", 7, "suspension", false, "2026-11-09 09:59:10");
        pending("L1", 8, "suspension", false, "2026-11-09 09:59:00");
        pending("L1", 8, "suspension", true, "2026-11-09 09:59:10");

        publication.publish("L1", 10);

        assertFalse(bit("L1", 7));
        assertTrue(bit("L1", 8));
    }

    @Test
    void signerFailure_rollsEverythingBack_andLeavesTheChangesPending() throws Exception {
        list("L1");
        pending("L1", 100, "revocation", true, "2026-11-09 09:59:00");
        String before = document("L1").toString();
        doThrow(new CertifyException("signing_failed")).when(statusListCredentialService).resignStatusListCredential(anyString());

        assertThrows(CertifyException.class, () -> publication.publish("L1", 10));

        assertEquals(before, document("L1").toString(), "document unchanged");
        assertEquals(1, count("SELECT count(*) FROM credential_status_transaction WHERE NOT is_processed"));
    }

    @Test
    void invalidPosition_failsThePublication_insteadOfBeingSkipped() {
        list("L1");
        pending("L1", 999999999L, "revocation", true, "2026-11-09 09:59:00");
        pending("L1", 5, "revocation", true, "2026-11-09 09:59:01");

        assertThrows(CertifyException.class, () -> publication.publish("L1", 10));

        assertEquals(2, count("SELECT count(*) FROM credential_status_transaction WHERE NOT is_processed"));
    }

    @Test
    void listLockedByAnotherNode_isLeftAlone_withoutWaiting() throws Exception {
        list("L1");
        pending("L1", 1, "revocation", true, "2026-11-09 09:59:00");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService other = Executors.newSingleThreadExecutor();
        Future<?> holder = other.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM status_list_credential WHERE id = 'L1' FOR UPDATE");
            locked.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertTrue(locked.await(30, TimeUnit.SECONDS));

        long started = System.currentTimeMillis();
        int result = publication.publish("L1", 10);
        long waited = System.currentTimeMillis() - started;

        assertEquals(StatusListPublicationService.LOCKED_BY_ANOTHER_NODE, result);
        assertTrue(waited < 5000, "must not wait for the other node: " + waited + " ms");
        assertEquals(1, count("SELECT count(*) FROM credential_status_transaction WHERE NOT is_processed"));
        release.countDown();
        holder.get(30, TimeUnit.SECONDS);
        other.shutdown();
        assertEquals(1, publication.publish("L1", 10), "and publishes once the other node is done");
    }

    // ---- idempotent revocation -----------------------------------------------------------------------

    @Test
    void concurrentRevocationsOfOnePosition_leaveExactlyOneTransaction() throws Exception {
        list("L1");
        int callers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> inserted = new java.util.ArrayList<>();
        for (int i = 0; i < callers; i++) {
            inserted.add(pool.submit(() -> {
                start.await();
                return new TransactionTemplate(transactionManager).execute(status ->
                        transactions.insertRevocationIfAbsent("urn:uuid:1", "L1", 42, LocalDateTime.now(ZoneOffset.UTC)));
            }));
        }
        start.countDown();
        int total = 0;
        for (Future<Integer> future : inserted) total += future.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(1, total, "exactly one caller inserted");
        assertEquals(1, count("SELECT count(*) FROM credential_status_transaction WHERE status_list_index = 42"));
        assertTrue(transactions.findFirstByStatusListCredentialIdAndStatusListIndexAndStatusPurposeAndStatusValueTrueOrderByTransactionLogIdAsc("L1", 42L, "revocation").isPresent());
    }

    @Test
    void otherPurposesAreNotSubjectToTheRevocationRule() {
        list("L1");
        pending("L1", 3, "suspension", true, "2026-11-09 09:59:00");
        pending("L1", 3, "suspension", true, "2026-11-09 09:59:01");
        assertEquals(2, count("SELECT count(*) FROM credential_status_transaction WHERE status_list_index = 3"));
    }

    // ---- the job ------------------------------------------------------------------------------------------

    @Test
    void jobDrainsABacklogAcrossBatchesAndLists_andOldestPendingAgeIsReported() throws Exception {
        list("L1");
        list("L2");
        for (int i = 0; i < 5; i++) pending("L1", 20 + i, "revocation", true, "2026-11-09 09:58:0" + i);
        for (int i = 0; i < 3; i++) pending("L2", 30 + i, "revocation", true, "2026-11-09 09:58:1" + i);
        assertEquals(LocalDateTime.parse("2026-11-09T09:58:00"), transactions.findOldestPendingTime().orElseThrow());
        assertEquals(LocalDateTime.parse("2026-11-09T09:58:10"), transactions.findOldestPendingTime("L2").orElseThrow());

        job.runOnce(); // batch size 2: needs several passes

        assertEquals(0, count("SELECT count(*) FROM credential_status_transaction WHERE NOT is_processed"));
        for (int i = 0; i < 5; i++) assertTrue(bit("L1", 20 + i));
        for (int i = 0; i < 3; i++) assertTrue(bit("L2", 30 + i));
        assertTrue(transactions.findOldestPendingTime().isEmpty());
    }

    @Test
    void aFailingListDoesNotStopTheOthers_andIsNotRetriedInTightLoop() throws Exception {
        list("BAD");
        list("GOOD");
        pending("BAD", 1, "revocation", true, "2026-11-09 09:58:00");
        pending("GOOD", 2, "revocation", true, "2026-11-09 09:58:05");
        doAnswer(invocation -> {
            String document = invocation.getArgument(0);
            if (document.contains("\"BAD\"")) throw new CertifyException("signing_failed");
            return new JSONObject(document).put("proof", new JSONObject().put("type", "test")).toString();
        }).when(statusListCredentialService).resignStatusListCredential(anyString());

        job.runOnce();

        assertTrue(bit("GOOD", 2));
        assertEquals(1, count("SELECT count(*) FROM credential_status_transaction WHERE NOT is_processed AND status_list_credential_id = 'BAD'"));
        assertFalse(bit("BAD", 1));
    }
}
