package io.mosip.certify.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosip.certify.config.IndexedAttributesConfig;
import io.mosip.certify.config.LedgerSearchProperties;
import io.mosip.certify.core.dto.CredentialLedgerSearchRequest;
import io.mosip.certify.core.dto.CredentialStatusResponse;
import io.mosip.certify.core.dto.LedgerSearchResult;
import io.mosip.certify.exception.ManagementApiException;
import io.mosip.certify.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Ledger search on a real PostgreSQL: JSONB filters, the document-id index, paging and isolation. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CredentialLedgerServiceImpl.class, IndexedAttributePolicy.class, IndexedAttributesConfig.class, LedgerSearchProperties.class})
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "mosip.certify.indexed-types[ERLCredential,VerifiableCredential].mappings[document_id]=$.license_no",
        "mosip.certify.indexed-types[ERLCredential,VerifiableCredential].mappings[vehicle_no]=$.vehicle_no",
        "mosip.certify.indexed-types[ERLCredential,VerifiableCredential].mappings[issued_date]=$.issued_date",
        "mosip.certify.indexed-types[ERLCredential,VerifiableCredential].searchable-fields=document_id,vehicle_no",
        "mosip.certify.indexed-types[DrivingLicence,VerifiableCredential].mappings[document_id]=$.drivers_license_id",
        "mosip.certify.indexed-types[DrivingLicence,VerifiableCredential].searchable-fields=document_id"
})
class LedgerSearchIntegrationTest {

    private static final String ISSUER = "did:web:issuer.example";
    private static final String ERL = "ERLCredential,VerifiableCredential";
    private static final String DRIVING = "DrivingLicence,VerifiableCredential";

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
    private CredentialLedgerServiceImpl service;

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM ledger");
    }

    // ---- helpers -------------------------------------------------------------------------------

    private int sequence;

    private void ledger(String credentialId, String issuer, String type, Map<String, String> attributes) {
        String json = new org.json.JSONObject(attributes).toString();
        jdbc.update("INSERT INTO ledger (credential_id, issuer_id, issuance_date, credential_type, indexed_attributes, credential_status_details) "
                        + "VALUES (?, ?, now(), ?, CAST(? AS jsonb), CAST(? AS jsonb))",
                credentialId, issuer, type, json,
                "[{\"status_purpose\":\"revocation\",\"status_list_credential_id\":\"L1\",\"status_list_index\":" + (sequence++) + ",\"cr_dtimes\":1}]");
    }

    private void erl(String credentialId, String documentId, String vehicle) {
        ledger(credentialId, ISSUER, ERL, Map.of("document_id", documentId, "vehicle_no", vehicle));
    }

    private CredentialLedgerSearchRequest request(String type, Map<String, String> filters, Integer limit, Integer offset) {
        CredentialLedgerSearchRequest request = new CredentialLedgerSearchRequest();
        request.setIssuerId(ISSUER);
        request.setCredentialType(type);
        request.setIndexedAttributesEquals(filters);
        request.setLimit(limit);
        request.setOffset(offset);
        return request;
    }

    private List<String> ids(LedgerSearchResult<CredentialStatusResponse> result) {
        return result.records().stream().map(CredentialStatusResponse::getCredentialId).toList();
    }

    private ManagementApiException refused(CredentialLedgerSearchRequest request) {
        return assertThrows(ManagementApiException.class, () -> service.searchCredentialLedger(request));
    }

    // ---- paging --------------------------------------------------------------------------------

    @Test
    void pagesNeitherOmitNorDuplicate_andFollowTheInternalRowOrder() {
        for (int i = 0; i < 25; i++) erl("vc-" + String.format("%02d", i), "D1", "WP-" + i); // repeated VCs for one document
        for (int i = 0; i < 7; i++) erl("other-" + i, "D2", "CP-" + i);

        List<String> seen = new ArrayList<>();
        List<Boolean> hasMore = new ArrayList<>();
        for (int offset = 0; offset < 30; offset += 10) {
            LedgerSearchResult<CredentialStatusResponse> page = service.searchCredentialLedger(request(ERL, Map.of("document_id", "D1"), 10, offset));
            assertTrue(page.paginated());
            seen.addAll(ids(page));
            hasMore.add(page.hasMore());
        }

        assertEquals(25, seen.size());
        assertEquals(25, new LinkedHashSet<>(seen).size(), "no duplicates");
        assertEquals(seen.stream().sorted(java.util.Comparator.reverseOrder()).toList(), seen,
                "newest first: ordered by ledger row id descending (reverse insertion order here)");
        assertEquals(List.of(true, true, false), hasMore);
    }

    @Test
    void pageBoundaries_exactFit_singleRow_andEmptyPage() {
        for (int i = 0; i < 10; i++) erl("vc-" + i, "D1", "V" + i);
        var exact = service.searchCredentialLedger(request(ERL, Map.of("document_id", "D1"), 10, 0));
        assertEquals(10, exact.records().size());
        assertFalse(exact.hasMore(), "exactly one full page is not 'more'");

        var single = service.searchCredentialLedger(request(ERL, Map.of("document_id", "D1"), 1, null));
        assertEquals(1, single.records().size());
        assertTrue(single.hasMore());

        var beyond = service.searchCredentialLedger(request(ERL, Map.of("document_id", "D1"), 10, 10));
        assertTrue(beyond.records().isEmpty());
        assertFalse(beyond.hasMore());
        assertTrue(beyond.paginated());
    }

    @Test
    void defaultsApplyWhenOnlyOneOfLimitOrOffsetIsSent() {
        for (int i = 0; i < 120; i++) erl("vc-" + String.format("%03d", i), "D1", "V" + i);

        var onlyOffset = service.searchCredentialLedger(request(ERL, Map.of("document_id", "D1"), null, 100));
        assertEquals(20, onlyOffset.records().size());
        assertTrue(onlyOffset.paginated());

        var onlyLimit = service.searchCredentialLedger(request(ERL, Map.of("document_id", "D1"), 50, null));
        assertEquals(50, onlyLimit.records().size());
        assertEquals("vc-119", onlyLimit.records().get(0).getCredentialId(), "newest first");
    }

    @Test
    void limitAndOffsetBounds() {
        erl("vc-1", "D1", "V1");
        Map<String, String> filter = Map.of("document_id", "D1");

        assertEquals(1, service.searchCredentialLedger(request(ERL, filter, 1000, 0)).records().size());
        assertTrue(service.searchCredentialLedger(request(ERL, filter, 1, 10000)).records().isEmpty());

        assertEquals("invalid_request", refused(request(ERL, filter, 1001, 0)).getErrorCode());
        assertEquals("invalid_request", refused(request(ERL, filter, 0, 0)).getErrorCode());
        assertEquals("invalid_request", refused(request(ERL, filter, -5, 0)).getErrorCode());
        assertEquals("invalid_request", refused(request(ERL, filter, 10, -1)).getErrorCode());
        ManagementApiException window = refused(request(ERL, filter, 10, 10001));
        assertEquals("search_window_exceeded", window.getErrorCode());
        assertTrue(window.getMessage().contains("document_id"), "tells the administrator how to narrow the search");
    }

    @Test
    void withoutPagingFields_returnsAllUpTo1000_andRefusesMore() {
        jdbc.update("INSERT INTO ledger (credential_id, issuer_id, issuance_date, credential_type, indexed_attributes) "
                + "SELECT 'big-' || g, ?, now(), ?, jsonb_build_object('document_id', 'BIG', 'vehicle_no', 'V' || g) "
                + "FROM generate_series(1, 1000) g", ISSUER, ERL);
        var all = service.searchCredentialLedger(request(ERL, Map.of("document_id", "BIG"), null, null));
        assertEquals(1000, all.records().size());
        assertFalse(all.paginated());
        assertFalse(all.hasMore());

        erl("big-1001", "BIG", "V1001");
        ManagementApiException error = refused(request(ERL, Map.of("document_id", "BIG"), null, null));
        assertEquals("pagination_required", error.getErrorCode());
        // And the same data is readable page by page.
        assertEquals(1, service.searchCredentialLedger(request(ERL, Map.of("document_id", "BIG"), 100, 1000)).records().size(),
                "the 1,001st match is on the page that starts at offset 1000");
    }

    // ---- filters -------------------------------------------------------------------------------

    @Test
    void vehicleOnly_documentOnly_andCombinedFiltersUseAnd() {
        erl("vc-a", "100", "WP-AAA");
        erl("vc-b", "100", "WP-BBB");
        erl("vc-c", "200", "WP-AAA");

        assertEquals(List.of("vc-c", "vc-a"), ids(service.searchCredentialLedger(request(ERL, Map.of("vehicle_no", "WP-AAA"), 10, 0))));
        assertEquals(List.of("vc-b", "vc-a"), ids(service.searchCredentialLedger(request(ERL, Map.of("document_id", "100"), 10, 0))));
        assertEquals("invalid_search_criteria", refused(request(ERL, Map.of("licence_id", "100"), 10, 0)).getErrorCode(), "the DMT row id is not indexed");
        assertEquals(List.of("vc-a"), ids(service.searchCredentialLedger(request(ERL, Map.of("vehicle_no", "WP-AAA", "document_id", "100"), 10, 0))));
        assertTrue(service.searchCredentialLedger(request(ERL, Map.of("vehicle_no", "WP-ZZZ"), 10, 0)).records().isEmpty());
        assertTrue(service.searchCredentialLedger(request(ERL, Map.of("vehicle_no", "WP-BBB", "document_id", "200"), 10, 0)).records().isEmpty());
    }

    @Test
    void credentialIdAloneIsEnough_andCombinesWithFilters() {
        erl("vc-a", "100", "WP-AAA");
        erl("vc-b", "100", "WP-BBB");
        CredentialLedgerSearchRequest byId = request(ERL, null, null, null);
        byId.setCredentialId("vc-b");
        assertEquals(List.of("vc-b"), ids(service.searchCredentialLedger(byId)));

        CredentialLedgerSearchRequest mismatch = request(ERL, Map.of("document_id", "999"), null, null);
        mismatch.setCredentialId("vc-b");
        assertTrue(service.searchCredentialLedger(mismatch).records().isEmpty());
    }

    @Test
    void blankMissingOrUnsupportedFilters_areRefused_notIgnored() {
        erl("vc-a", "100", "WP-AAA");
        assertEquals("invalid_search_criteria", refused(request(ERL, null, 10, 0)).getErrorCode());
        assertEquals("invalid_search_criteria", refused(request(ERL, Map.of(), 10, 0)).getErrorCode());
        assertEquals("invalid_search_criteria", refused(request(ERL, Map.of("document_id", " "), 10, 0)).getErrorCode());
        assertEquals("invalid_search_criteria", refused(request(ERL, Map.of("issued_date", "2026-10-16T14:30:00"), 10, 0)).getErrorCode());
        assertEquals("invalid_search_criteria", refused(request(ERL, Map.of("owner_name", "x"), 10, 0)).getErrorCode());
        CredentialLedgerSearchRequest blankId = request(ERL, null, null, null);
        blankId.setCredentialId("  ");
        assertEquals("invalid_search_criteria", refused(blankId).getErrorCode());
    }

    @Test
    void sameDocumentIdInAnotherIssuerOrTypeStaysIsolated() {
        erl("erl-1", "42", "WP-1");
        ledger("other-issuer", "did:web:another.example", ERL, Map.of("document_id", "42"));
        ledger("driving-1", ISSUER, DRIVING, Map.of("document_id", "42"));

        assertEquals(List.of("erl-1"), ids(service.searchCredentialLedger(request(ERL, Map.of("document_id", "42"), 10, 0))));
        assertEquals(List.of("driving-1"), ids(service.searchCredentialLedger(request(DRIVING, Map.of("document_id", "42"), 10, 0))));
    }

    @Test
    void leadingZerosAreSignificant() {
        erl("vc-zero", "000123", "V1");
        erl("vc-plain", "123", "V2");
        assertEquals(List.of("vc-zero"), ids(service.searchCredentialLedger(request(ERL, Map.of("document_id", "000123"), 10, 0))));
        assertEquals(List.of("vc-plain"), ids(service.searchCredentialLedger(request(ERL, Map.of("document_id", "123"), 10, 0))));
    }

    @Test
    void secondDocumentType_isSearchableByItsOwnConfiguration_withoutCodeChanges() {
        ledger("dl-1", ISSUER, DRIVING, Map.of("document_id", "DL-0099"));
        assertEquals(List.of("dl-1"), ids(service.searchCredentialLedger(request(DRIVING, Map.of("document_id", "DL-0099"), 10, 0))));
        assertEquals("invalid_search_criteria", refused(request(DRIVING, Map.of("vehicle_no", "x"), 10, 0)).getErrorCode());
    }

    // ---- query plan ----------------------------------------------------------------------------

    @Test
    void documentIdSearch_usesTheDocumentIndex_onASeededTable() {
        jdbc.update("INSERT INTO ledger (credential_id, issuer_id, issuance_date, credential_type, indexed_attributes) "
                + "SELECT 'seed-' || g, ?, now(), ?, jsonb_build_object('document_id', (g % 20000)::text, 'vehicle_no', 'V' || g) "
                + "FROM generate_series(1, 60000) g", ISSUER, ERL);
        jdbc.execute("ANALYZE ledger");

        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM ledger WHERE issuer_id = '" + ISSUER
                + "' AND credential_type = '" + ERL + "' AND indexed_attributes->>'document_id' = '777' ORDER BY id DESC LIMIT 101 OFFSET 0");
        String text = plan.stream().map(row -> String.valueOf(row.get("QUERY PLAN"))).reduce("", (a, b) -> a + "\n" + b);

        assertTrue(text.contains("idx_ledger_document_page"), "expected the document index in the plan:\n" + text);
        assertFalse(text.contains("Seq Scan"), "a sequential scan means the index is not serving the query:\n" + text);
        assertFalse(text.contains("Sort"), "the index already provides the id ordering:\n" + text);
    }
}
