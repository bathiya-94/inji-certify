package io.mosip.certify.services;

import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.entity.StatusListCredential;
import io.mosip.certify.testsupport.PostgresTestSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Runs the status list allocator against a real PostgreSQL (row locks, partial indexes, advisory locks and
 * UPDATE ... RETURNING behave differently on H2). Needs Docker; skipped when it is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({DatabaseStatusListIndexProvider.class, StatusListAllocationService.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "mosip.certify.data-provider-plugin.did-url=did:web:test.example",
        "mosip.certify.statuslist.size-in-kb=1",
        "mosip.certify.statuslist.usable-capacity-percentage=1"
})
class StatusListAllocationIntegrationTest {

    private static final String ISSUER = "did:web:test.example";
    private static final String PURPOSE = "revocation";
    private static final int POSITIONS = 8192; // 1 KiB list

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
    private DatabaseStatusListIndexProvider provider;
    @Autowired
    private StatusListAllocationService allocationService;
    @Autowired
    private io.mosip.certify.repository.StatusListAvailableIndicesRepository positions;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockBean
    private StatusListCredentialService statusListCredentialService;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM status_list_credential"); // cascades to positions and allocations
        when(statusListCredentialService.generateStatusListCredential(anyString()))
                .thenAnswer(invocation -> createList(false, 0, 0));
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Inserts a 1 KiB list with all positions free. Optionally also an allocation row with a small limit. */
    private StatusListCredential createList(boolean withAllocation, long limit, long allocated) {
        String id = java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO status_list_credential (id, vc_document, credential_type, status_purpose, capacity_in_kb, credential_status) "
                + "VALUES (?, '{}', 'BitstringStatusListCredential', ?, 1, CAST('AVAILABLE' AS credential_status_enum))", id, PURPOSE);
        jdbc.update("INSERT INTO status_list_available_indices (status_list_credential_id, list_index, is_assigned) "
                + "SELECT ?, generate_series(0, ? - 1), false", id, POSITIONS);
        if (withAllocation) {
            jdbc.update("INSERT INTO status_list_allocation (status_list_credential_id, issuer_id, status_purpose, physical_capacity, "
                    + "allocation_limit, allocated_count, is_active) VALUES (?, ?, ?, ?, ?, ?, true)",
                    id, ISSUER, PURPOSE, POSITIONS, limit, allocated);
        }
        StatusListCredential list = new StatusListCredential();
        list.setId(id);
        list.setCapacityInKB(1L);
        return list;
    }

    private <T> List<T> runConcurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> future : futures) {
            results.add(future.get(120, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ---- scenarios -----------------------------------------------------------------------------

    @Test
    void simultaneousClaimsOnOneList_neverShareAPosition() throws Exception {
        String listId = createList(true, 1000, 0).getId();

        List<Optional<IndexClaim>> claims = runConcurrently(40, () -> provider.acquireIndex(listId, java.util.Map.of()));

        Set<Long> indexes = new java.util.HashSet<>();
        for (Optional<IndexClaim> claim : claims) {
            assertTrue(claim.isPresent());
            assertTrue(indexes.add(claim.get().index()), "position handed out twice: " + claim.get().index());
        }
        assertEquals(40, count("SELECT allocated_count FROM status_list_allocation WHERE status_list_credential_id = ?", listId));
        assertEquals(40, count("SELECT count(*) FROM status_list_available_indices WHERE status_list_credential_id = ? AND is_assigned", listId));
    }

    @Test
    void oneAllocationRemains_exactlyOneRequestTakesItAndOthersUndoTheirClaim() throws Exception {
        String listId = createList(true, 10, 9).getId();
        jdbc.update("UPDATE status_list_available_indices SET is_assigned = true WHERE status_list_credential_id = ? AND list_index < 9", listId);

        List<Optional<IndexClaim>> claims = runConcurrently(20, () -> provider.acquireIndex(listId, java.util.Map.of()));

        long succeeded = claims.stream().filter(Optional::isPresent).count();
        assertEquals(1, succeeded);
        assertTrue(claims.stream().filter(Optional::isPresent).findFirst().get().get().listClosed());
        assertEquals(10, count("SELECT allocated_count FROM status_list_allocation WHERE status_list_credential_id = ?", listId));
        assertEquals(10, count("SELECT count(*) FROM status_list_available_indices WHERE status_list_credential_id = ? AND is_assigned", listId),
                "requests that lost the last allocation must not keep their position");
        assertFalse(jdbc.queryForObject("SELECT is_active FROM status_list_allocation WHERE status_list_credential_id = ?", Boolean.class, listId));
    }

    @Test
    void closedList_returnsEmptyWithoutClaimingAnything() {
        String listId = createList(true, 5, 5).getId();
        jdbc.update("UPDATE status_list_allocation SET is_active = false WHERE status_list_credential_id = ?", listId);

        assertTrue(provider.acquireIndex(listId, java.util.Map.of()).isEmpty());
        assertEquals(0, count("SELECT count(*) FROM status_list_available_indices WHERE status_list_credential_id = ? AND is_assigned", listId));
    }

    @Test
    void missingAllocationRow_isAnError_notAnInstructionToCreateAList() {
        String listId = createList(false, 0, 0).getId();
        // A free position exists, but no allocation record: the counter update finds no row.
        assertTrue(provider.acquireIndex(listId, java.util.Map.of()).isEmpty());
        assertEquals(0, count("SELECT count(*) FROM status_list_available_indices WHERE status_list_credential_id = ? AND is_assigned", listId),
                "the claim must be rolled back");
        // And with no free position either, the missing record is reported as an error.
        jdbc.update("UPDATE status_list_available_indices SET is_assigned = true WHERE status_list_credential_id = ?", listId);
        assertThrows(CertifyException.class, () -> provider.acquireIndex(listId, java.util.Map.of()));
    }

    @Test
    void noFreePositionButCapacityRemains_isRetryableError_notFull() {
        String listId = createList(true, 100, 3).getId();
        jdbc.update("UPDATE status_list_available_indices SET is_assigned = true WHERE status_list_credential_id = ?", listId);

        CertifyException error = assertThrows(CertifyException.class, () -> provider.acquireIndex(listId, java.util.Map.of()));
        assertEquals("status_list_index_unavailable", error.getErrorCode());
        assertTrue(jdbc.queryForObject("SELECT is_active FROM status_list_allocation WHERE status_list_credential_id = ?", Boolean.class, listId));
    }

    @Test
    void noActiveList_concurrentCreators_commitExactlyOneList() throws Exception {
        runConcurrently(8, () -> {
            allocationService.ensureActiveList(PURPOSE);
            return null;
        });

        assertEquals(1, count("SELECT count(*) FROM status_list_allocation WHERE is_active"));
        assertEquals(1, count("SELECT count(*) FROM status_list_credential"));
        assertEquals(POSITIONS, count("SELECT count(*) FROM status_list_available_indices"),
                "the new list must be fully initialised before it is active");
    }

    @Test
    void concurrentRollover_reusesOneReplacement_andNeverExceedsAnyLimit() throws Exception {
        // 1% of 8192 = 81 allocations per list. 200 allocations need three lists.
        AtomicInteger succeeded = new AtomicInteger();
        runConcurrently(20, () -> {
            for (int i = 0; i < 10; i++) {
                allocationService.allocate(PURPOSE);
                succeeded.incrementAndGet();
            }
            return null;
        });

        assertEquals(200, succeeded.get());
        assertEquals(3, count("SELECT count(*) FROM status_list_allocation"));
        assertEquals(1, count("SELECT count(*) FROM status_list_allocation WHERE is_active"));
        assertEquals(0, count("SELECT count(*) FROM status_list_allocation WHERE allocated_count > allocation_limit"));
        assertEquals(200, count("SELECT sum(allocated_count) FROM status_list_allocation"));
        assertEquals(200, count("SELECT count(*) FROM status_list_available_indices WHERE is_assigned"));
        assertEquals(2, count("SELECT count(*) FROM status_list_allocation WHERE NOT is_active AND allocated_count = allocation_limit"));
        assertEquals(2, count("SELECT count(*) FROM status_list_credential WHERE credential_status = 'FULL'"),
                "closed lists are flagged FULL when the replacement is created");
    }

    @Test
    void failedCreation_leavesNoPartialList_andNextRequestRetries() {
        when(statusListCredentialService.generateStatusListCredential(anyString()))
                .thenThrow(new CertifyException("status_list_generation_failed"))
                .thenAnswer(invocation -> createList(false, 0, 0));

        assertThrows(CertifyException.class, () -> allocationService.ensureActiveList(PURPOSE));
        assertEquals(0, count("SELECT count(*) FROM status_list_allocation"));

        AllocatedPosition position = allocationService.allocate(PURPOSE);
        assertNotNull(position.statusListId());
        assertEquals(1, count("SELECT count(*) FROM status_list_allocation WHERE is_active"));
    }

    @Test
    void confirmIssued_onlyForAReservedPositionThatIsNotYetConfirmed() {
        String listId = createList(true, 100, 0).getId();
        long reserved = provider.acquireIndex(listId, java.util.Map.of()).orElseThrow().index();
        long neverReserved = jdbc.queryForObject("SELECT min(list_index) FROM status_list_available_indices "
                + "WHERE status_list_credential_id = ? AND NOT is_assigned", Long.class, listId);
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();

        assertEquals(1, (int) tx.execute(status -> positions.confirmIssued(listId, reserved, "urn:uuid:1", now)));
        assertEquals(0, (int) tx.execute(status -> positions.confirmIssued(listId, reserved, "urn:uuid:2", now)), "already confirmed");
        assertEquals(0, (int) tx.execute(status -> positions.confirmIssued(listId, neverReserved, "urn:uuid:3", now)), "never reserved");
        assertEquals("urn:uuid:1", jdbc.queryForObject(
                "SELECT credential_id FROM status_list_available_indices WHERE status_list_credential_id = ? AND list_index = ?",
                String.class, listId, reserved));
    }
}
