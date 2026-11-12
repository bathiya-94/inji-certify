package io.mosip.certify.services;

import foundation.identity.jsonld.JsonLDObject;
import io.mosip.certify.api.dto.VCResult;
import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.constants.VCDM2Constants;
import io.mosip.certify.core.constants.VCFormats;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.credential.Credential;
import io.mosip.certify.credential.CredentialFactory;
import io.mosip.certify.credential.W3CJsonLD;
import io.mosip.certify.entity.StatusListCredential;
import io.mosip.certify.core.dto.CredentialStatusDetail;
import io.mosip.certify.repository.LedgerRepository;
import io.mosip.certify.repository.StatusListAvailableIndicesRepository;
import io.mosip.certify.repository.StatusListCredentialRepository;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.*;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class StatusListCredentialServiceTest {

    @InjectMocks
    private StatusListCredentialService service;

    @Mock
    private StatusListCredentialRepository statusListCredentialRepository;
    @Mock
    private CredentialFactory credentialFactory;
    @Mock
    private StatusListAllocationService allocationService;
    @Mock
    private EntityManager entityManager;
    @Mock
    private Credential credential;
    @Mock
    private io.mosip.certify.repository.CredentialStatusTransactionRepository transactionRepository;
    private final io.mosip.certify.config.StatusListPublicationProperties publicationProperties = new io.mosip.certify.config.StatusListPublicationProperties();

    @Before
    public void setUp() {
        ReflectionTestUtils.setField(service, "statusListCredentialRepository", statusListCredentialRepository);
        ReflectionTestUtils.setField(service, "credentialFactory", credentialFactory);
        ReflectionTestUtils.setField(service, "allocationService", allocationService);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "publicationProperties", publicationProperties);
        ReflectionTestUtils.setField(service, "transactionRepository", transactionRepository);
        ReflectionTestUtils.setField(service, "didUrl", "did:example:issuer");
        ReflectionTestUtils.setField(service, "domainUrl", "https://example.com");
        ReflectionTestUtils.setField(service, "statusListSizeInKB", 2L);
        ReflectionTestUtils.setField(service, "signatureCryptoSuite", "Ed25519Signature2020");
        ReflectionTestUtils.setField(service, "signatureAlgo", "EdDSA");
        ReflectionTestUtils.setField(service, "statusListKeyManagerRefId", "ED25519_SIGN");
        Map<String, List<List<String>>> keyAliasMapper = new HashMap<>();
        keyAliasMapper.put("EdDSA", Arrays.asList(Arrays.asList("appId")));
        ReflectionTestUtils.setField(service, "keyAliasMapper", keyAliasMapper);
    }

    @Test
    public void getStatusListCredential_ReturnsVC() {
        StatusListCredential slc = new StatusListCredential();
        slc.setId("id1");
        slc.setVcDocument("{\"foo\":\"bar\"}");
        when(statusListCredentialRepository.findById("id1")).thenReturn(Optional.of(slc));
        String result = service.getStatusListCredential("id1");
        assertTrue(result.contains("foo"));
    }

    @Test
    public void getStatusListCredential_NotFound_Throws() {
        when(statusListCredentialRepository.findById("id2")).thenReturn(Optional.empty());
        try {
            service.getStatusListCredential("id2");
            fail("Expected CertifyException");
        } catch (CertifyException ex) {
            assertEquals(ErrorConstants.STATUS_LIST_NOT_FOUND, ex.getErrorCode());
        }
    }

    @Test
    public void getStatusListCredential_JsonError_Throws() {
        StatusListCredential slc = new StatusListCredential();
        slc.setId("id3");
        slc.setVcDocument("not-json");
        when(statusListCredentialRepository.findById("id3")).thenReturn(Optional.of(slc));
        try {
            service.getStatusListCredential("id3");
            fail("Expected CertifyException");
        } catch (CertifyException ex) {
            // expected
        }
    }

    @Test
    public void findStatusListById_ReturnsOptional() {
        StatusListCredential slc = new StatusListCredential();
        slc.setId("id4");
        when(statusListCredentialRepository.findById("id4")).thenReturn(Optional.of(slc));
        Optional<StatusListCredential> result = service.findStatusListById("id4");
        assertTrue(result.isPresent());
    }

    @Test
    public void findStatusListById_Exception_ReturnsEmpty() {
        when(statusListCredentialRepository.findById("id5")).thenThrow(new RuntimeException("db error"));
        Optional<StatusListCredential> result = service.findStatusListById("id5");
        assertFalse(result.isPresent());
    }

    @Test
    public void generateStatusListCredential_Success() {
        W3CJsonLD w3CJsonLD = mock(W3CJsonLD.class);
        when(credentialFactory.getCredential(VCFormats.LDP_VC)).thenReturn(Optional.of(w3CJsonLD));
        VCResult mockVcResultLdp = new VCResult<JsonLDObject>();
        JsonLDObject signedCredObj = JsonLDObject.fromJson("{\"signed\":\"credential\", \"proof\":{}}");
        mockVcResultLdp.setCredential(signedCredObj);

        when(w3CJsonLD.addProof(
                anyString(),
                eq(""),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(mockVcResultLdp);
        when(statusListCredentialRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        // Mock entityManager for initializeAvailableIndices
        Query mockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyInt(), any())).thenReturn(mockQuery);
        when(mockQuery.getSingleResult()).thenReturn(1L);
        when(mockQuery.executeUpdate()).thenReturn(2);

        StatusListCredential result = service.generateStatusListCredential("revocation");
        assertEquals("revocation", result.getStatusPurpose());
    }

    @Test
    public void generateStatusListCredential_JsonError_Throws() {
        when(credentialFactory.getCredential(VCFormats.LDP_VC)).thenReturn(Optional.of(credential));
        when(credential.addProof(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("json error"));
        try {
            service.generateStatusListCredential("revocation");
            fail("Expected CertifyException");
        } catch (CertifyException ex) {
            // expected
        }
    }

    @Test
    public void generateStatusListCredential_KeyAliasNotConfigured_Throws() {
        ReflectionTestUtils.setField(service, "signatureCryptoSuite", "Ed25519Signature20201");
        try {
            service.generateStatusListCredential("revocation");
            fail("Expected CertifyException");
        } catch (CertifyException ex) {
            assertEquals(ErrorConstants.STATUS_LIST_GENERATION_FAILED, ex.getErrorCode());
        }
    }

    @Test
    public void addCredentialStatus_AddsReservedPositionToCredential() {
        when(allocationService.allocate("revocation")).thenReturn(new AllocatedPosition("slid", 42L));
        JSONObject json = new JSONObject();

        service.addCredentialStatus(json, "revocation");

        JSONObject status = json.getJSONObject(VCDM2Constants.CREDENTIAL_STATUS);
        assertEquals("https://example.com/v1/certify/credentials/status-list/slid#42", status.getString("id"));
        assertEquals("BitstringStatusListEntry", status.getString("type"));
        assertEquals("revocation", status.getString("statusPurpose"));
        assertEquals("42", status.getString("statusListIndex"));
        assertEquals("https://example.com/v1/certify/credentials/status-list/slid", status.getString("statusListCredential"));
    }

    @Test
    public void addCredentialStatus_AllocationFailure_Propagates() {
        when(allocationService.allocate("revocation"))
                .thenThrow(new CertifyException(ErrorConstants.STATUS_LIST_INDEX_UNAVAILABLE));
        JSONObject json = new JSONObject();

        CertifyException ex = assertThrows(CertifyException.class, () -> service.addCredentialStatus(json, "revocation"));

        assertEquals(ErrorConstants.STATUS_LIST_INDEX_UNAVAILABLE, ex.getErrorCode());
        assertFalse(json.has(VCDM2Constants.CREDENTIAL_STATUS));
    }

    @Test
    public void generateStatusListCredential_SignsTheTtlIntoTheList() {
        W3CJsonLD w3CJsonLD = mock(W3CJsonLD.class);
        when(credentialFactory.getCredential(VCFormats.LDP_VC)).thenReturn(Optional.of(w3CJsonLD));
        VCResult mockVcResultLdp = new VCResult<JsonLDObject>();
        mockVcResultLdp.setCredential(JsonLDObject.fromJson("{\"signed\":\"credential\", \"proof\":{}}"));
        ArgumentCaptor<String> unsigned = ArgumentCaptor.forClass(String.class);
        when(w3CJsonLD.addProof(unsigned.capture(), eq(""), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mockVcResultLdp);
        when(statusListCredentialRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        Query mockQuery = mock(Query.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(mockQuery);
        when(mockQuery.setParameter(anyInt(), any())).thenReturn(mockQuery);
        when(mockQuery.getSingleResult()).thenReturn(1L);
        when(mockQuery.executeUpdate()).thenReturn(2);

        service.generateStatusListCredential("revocation");

        JSONObject subject = new JSONObject(unsigned.getValue()).getJSONObject("credentialSubject");
        assertEquals(20000L, subject.getLong("ttl"));
        String validFrom = new JSONObject(unsigned.getValue()).getString("validFrom");
        assertTrue(validFrom, validFrom.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z"));
        assertEquals("revocation", subject.getString("statusPurpose"));
    }

    @Test
    public void utcTimestamp_AlwaysUsesTheSignersFormat() {
        // Instant.toString() would print microseconds here, or no fraction at all for the second one.
        assertEquals("2026-11-09T10:00:00.043Z", StatusListCredentialService.utcTimestamp(java.time.Instant.parse("2026-11-09T10:00:00.043813Z")));
        assertEquals("2026-11-09T10:00:00.000Z", StatusListCredentialService.utcTimestamp(java.time.Instant.parse("2026-11-09T10:00:00Z")));
    }

    private StatusListCredential published(String id, String document) {
        StatusListCredential list = new StatusListCredential();
        list.setId(id);
        list.setVcDocument(document);
        return list;
    }

    @Test
    public void getPublishedStatusList_ServesTheListWithAStableStrongEtag() {
        when(statusListCredentialRepository.findById("L1")).thenReturn(Optional.of(published("L1", "{\"a\":1}")));
        when(transactionRepository.findOldestPendingTime("L1")).thenReturn(Optional.empty());

        StatusListCredentialService.PublishedStatusList first = service.getPublishedStatusList("L1");
        StatusListCredentialService.PublishedStatusList second = service.getPublishedStatusList("L1");

        assertEquals("{\"a\":1}", first.body());
        assertTrue(first.etag().matches("\"[0-9a-f]{64}\""));
        assertEquals(first.etag(), second.etag());

        when(statusListCredentialRepository.findById("L1")).thenReturn(Optional.of(published("L1", "{\"a\":2}")));
        assertNotEquals(first.etag(), service.getPublishedStatusList("L1").etag());
    }

    @Test
    public void getPublishedStatusList_UnknownList_Is404() {
        when(statusListCredentialRepository.findById("nope")).thenReturn(Optional.empty());
        io.mosip.certify.exception.ManagementApiException error = assertThrows(io.mosip.certify.exception.ManagementApiException.class,
                () -> service.getPublishedStatusList("nope"));
        assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals(ErrorConstants.STATUS_LIST_NOT_FOUND, error.getErrorCode());
    }

    @Test
    public void getPublishedStatusList_RecentPendingChange_StillServed() {
        when(statusListCredentialRepository.findById("L1")).thenReturn(Optional.of(published("L1", "{}")));
        when(transactionRepository.findOldestPendingTime("L1"))
                .thenReturn(Optional.of(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(60)));
        assertNotNull(service.getPublishedStatusList("L1"));
    }

    @Test
    public void getPublishedStatusList_ChangeUnpublishedTooLong_Is503() {
        when(statusListCredentialRepository.findById("L1")).thenReturn(Optional.of(published("L1", "{}")));
        when(transactionRepository.findOldestPendingTime("L1"))
                .thenReturn(Optional.of(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(91)));
        io.mosip.certify.exception.ManagementApiException error = assertThrows(io.mosip.certify.exception.ManagementApiException.class,
                () -> service.getPublishedStatusList("L1"));
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertEquals(ErrorConstants.STATUS_LIST_UNAVAILABLE, error.getErrorCode());
    }

    @Test
    public void resignStatusListCredential_Success() {
        W3CJsonLD w3CJsonLD = mock(W3CJsonLD.class);
        when(credentialFactory.getCredential(VCFormats.LDP_VC)).thenReturn(Optional.of(w3CJsonLD));
        VCResult mockVcResultLdp = new VCResult<JsonLDObject>();
        JsonLDObject signedCredObj = JsonLDObject.fromJson("{\"signed\":\"credential\", \"proof\":{}}");
        mockVcResultLdp.setCredential(signedCredObj);

        // The holderId argument to addProof in the service is "" for LDP
        when(w3CJsonLD.addProof(
                anyString(),
                eq(""),  // Service code passes "" for LDP's addProof holderId
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(mockVcResultLdp);
        String input = "{\"unsigned\":\"credential\"}";
        String result = service.resignStatusListCredential(input);
        assertNotNull(result);
    }

    @Test
    public void resignStatusListCredential_Error_Throws() {
        when(credentialFactory.getCredential(VCFormats.LDP_VC)).thenReturn(Optional.of(credential));
        when(credential.addProof(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("fail"));
        try {
            service.resignStatusListCredential("{\"foo\":\"bar\"}");
            fail("Expected CertifyException");
        } catch (CertifyException ex) {
            // expected
        }
    }

}