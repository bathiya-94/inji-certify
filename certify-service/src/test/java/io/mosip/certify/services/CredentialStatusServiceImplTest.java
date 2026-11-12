package io.mosip.certify.services;

import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.dto.CredentialStatusDetail;
import io.mosip.certify.core.dto.CredentialStatusResponse;
import io.mosip.certify.core.dto.UpdateCredentialStatusRequest;
import io.mosip.certify.core.dto.UpdateCredentialStatusRequestV2;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.entity.CredentialStatusTransaction;
import io.mosip.certify.entity.Ledger;
import io.mosip.certify.entity.StatusListAvailableIndices;
import io.mosip.certify.entity.StatusListCredential;
import io.mosip.certify.exception.ManagementApiException;
import io.mosip.certify.repository.CredentialStatusTransactionRepository;
import io.mosip.certify.repository.LedgerRepository;
import io.mosip.certify.repository.StatusListAvailableIndicesRepository;
import io.mosip.certify.repository.StatusListCredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CredentialStatusServiceImplTest {

    private static final String LIST = "list-1";
    private static final String LIST_URL = "https://example.com/v1/certify/credentials/status-list/" + LIST;
    private static final String CREDENTIAL = "urn:uuid:67823e96-fda0-4eba-9828-a32a8d22cc42";

    @Mock
    private LedgerRepository ledgerRepository;
    @Mock
    private CredentialStatusTransactionRepository transactions;
    @Mock
    private StatusListCredentialRepository statusLists;
    @Mock
    private StatusListAvailableIndicesRepository positions;
    @InjectMocks
    private CredentialStatusServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        ReflectionTestUtils.setField(service, "allowedCredentialStatusPurposes", List.of("revocation", "suspension"));
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static CredentialStatusDetail detail(String purpose, long index) {
        return new CredentialStatusDetail(purpose, LIST, index, System.currentTimeMillis());
    }

    private static Ledger ledger(CredentialStatusDetail... details) {
        Ledger ledger = new Ledger();
        ledger.setCredentialId(CREDENTIAL);
        ledger.setIssuerId("did:web:issuer");
        ledger.setCredentialType("ERLCredential,VerifiableCredential");
        ledger.setIssuanceDate(LocalDateTime.parse("2026-11-09T10:00:00"));
        ledger.setCredentialStatusDetails(new ArrayList<>(List.of(details)));
        return ledger;
    }

    private static UpdateCredentialStatusRequest v1(String purpose, boolean status) {
        UpdateCredentialStatusRequest.CredentialStatusDto dto = new UpdateCredentialStatusRequest.CredentialStatusDto();
        dto.setStatusPurpose(purpose);
        UpdateCredentialStatusRequest request = new UpdateCredentialStatusRequest();
        request.setCredentialId(CREDENTIAL);
        request.setCredentialStatus(dto);
        request.setStatus(status);
        return request;
    }

    private static UpdateCredentialStatusRequestV2 v2(String purpose, long index, boolean status) {
        UpdateCredentialStatusRequestV2.CredentialStatusDtoV2 dto = new UpdateCredentialStatusRequestV2.CredentialStatusDtoV2();
        dto.setStatusPurpose(purpose);
        dto.setStatusListIndex(index);
        dto.setStatusListCredential(LIST);
        UpdateCredentialStatusRequestV2 request = new UpdateCredentialStatusRequestV2();
        request.setCredentialStatus(dto);
        request.setStatus(status);
        return request;
    }

    private CredentialStatusTransaction acknowledged(String purpose, boolean value, long index) {
        CredentialStatusTransaction transaction = new CredentialStatusTransaction();
        transaction.setTransactionLogId(7L);
        transaction.setStatusPurpose(purpose);
        transaction.setStatusValue(value);
        transaction.setStatusListCredentialId(LIST);
        transaction.setStatusListIndex(index);
        transaction.setCreatedDtimes(LocalDateTime.parse("2026-11-09T10:05:00"));
        return transaction;
    }

    private void revocationRecorded(long index) {
        when(transactions.insertRevocationIfAbsent(any(), eq(LIST), eq(index), any())).thenReturn(1);
        when(transactions.findFirstByStatusListCredentialIdAndStatusListIndexAndStatusPurposeAndStatusValueTrueOrderByTransactionLogIdAsc(LIST, index, "revocation"))
                .thenReturn(Optional.of(acknowledged("revocation", true, index)));
    }

    private ManagementApiException refusedWith(String code, Runnable call) {
        ManagementApiException error = assertThrows(ManagementApiException.class, call::run);
        assertEquals(code, error.getErrorCode());
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        return error;
    }

    // ---- v1: by credential id --------------------------------------------------------------------

    @Test
    void v1_unknownCredential_is404() {
        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.empty());

        ManagementApiException error = assertThrows(ManagementApiException.class, () -> service.updateCredentialStatus(v1("revocation", true)));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals(ErrorConstants.CREDENTIAL_NOT_FOUND, error.getErrorCode());
    }

    @Test
    void v1_revokes_andAcknowledgesTheQueuedChange() {
        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.of(ledger(detail("revocation", 87823))));
        revocationRecorded(87823);

        CredentialStatusResponse response = service.updateCredentialStatus(v1("revocation", true));

        assertEquals(CREDENTIAL, response.getCredentialId());
        assertEquals("revocation", response.getStatusPurpose());
        assertEquals(87823L, response.getStatusListIndex());
        assertEquals(LIST, response.getStatusListCredentialUrl());
        assertEquals("ERLCredential,VerifiableCredential", response.getCredentialType());
        assertEquals(LocalDateTime.parse("2026-11-09T10:05:00"), response.getStatusTimestamp());
        verify(transactions).insertRevocationIfAbsent(eq(CREDENTIAL), eq(LIST), eq(87823L), any(LocalDateTime.class));
        verify(transactions, never()).save(any());
    }

    @Test
    void v1_repeatedRevocation_returnsTheEarlierAcknowledgement_withoutQueuingAgain() {
        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.of(ledger(detail("revocation", 5))));
        when(transactions.insertRevocationIfAbsent(any(), any(), anyLong(), any())).thenReturn(0); // already revoked
        when(transactions.findFirstByStatusListCredentialIdAndStatusListIndexAndStatusPurposeAndStatusValueTrueOrderByTransactionLogIdAsc(LIST, 5L, "revocation"))
                .thenReturn(Optional.of(acknowledged("revocation", true, 5)));

        CredentialStatusResponse again = service.updateCredentialStatus(v1("revocation", true));

        assertEquals(LocalDateTime.parse("2026-11-09T10:05:00"), again.getStatusTimestamp(), "the first request's time");
        verify(transactions, never()).save(any());
    }

    @Test
    void v1_choosesTheEntryMatchingThePurpose() {
        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.of(ledger(detail("suspension", 1), detail("revocation", 2))));
        revocationRecorded(2);

        assertEquals(2L, service.updateCredentialStatus(v1("revocation", true)).getStatusListIndex());
    }

    @Test
    void v1_purposeTheCredentialDoesNotHave_isRefused() {
        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.of(ledger(detail("revocation", 2))));
        refusedWith(ErrorConstants.STATUS_PURPOSE_MISMATCH, () -> service.updateCredentialStatus(v1("suspension", true)));
    }

    @Test
    void v1_withoutAPurpose_needsAnUnambiguousEntry() {
        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.of(ledger(detail("suspension", 1), detail("revocation", 2))));
        refusedWith(ErrorConstants.AMBIGUOUS_STATUS_PURPOSE, () -> service.updateCredentialStatus(v1(null, true)));

        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.of(ledger(detail("revocation", 2))));
        revocationRecorded(2);
        assertEquals("revocation", service.updateCredentialStatus(v1(null, true)).getStatusPurpose());
    }

    @Test
    void v1_revocationCannotBeUndone() {
        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.of(ledger(detail("revocation", 2))));
        refusedWith(ErrorConstants.REVOCATION_CANNOT_BE_UNDONE, () -> service.updateCredentialStatus(v1("revocation", false)));
        verifyNoInteractions(transactions);
    }

    @Test
    void v1_purposeThatIsNotEnabled_isRefused() {
        ReflectionTestUtils.setField(service, "allowedCredentialStatusPurposes", List.of("revocation"));
        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.of(ledger(detail("suspension", 3))));
        refusedWith(ErrorConstants.STATUS_PURPOSE_NOT_ALLOWED, () -> service.updateCredentialStatus(v1("suspension", true)));
    }

    @Test
    void v1_aReferenceThatContradictsTheCredential_isRefused() {
        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.of(ledger(detail("revocation", 2))));
        UpdateCredentialStatusRequest wrongIndex = v1("revocation", true);
        wrongIndex.getCredentialStatus().setStatusListIndex(999L);
        refusedWith(ErrorConstants.STATUS_ID_MISMATCH, () -> service.updateCredentialStatus(wrongIndex));

        UpdateCredentialStatusRequest wrongList = v1("revocation", true);
        wrongList.getCredentialStatus().setStatusListCredential("https://example.com/v1/certify/credentials/status-list/other");
        refusedWith(ErrorConstants.STATUS_ID_MISMATCH, () -> service.updateCredentialStatus(wrongList));

        UpdateCredentialStatusRequest sameListAsUrl = v1("revocation", true);
        sameListAsUrl.getCredentialStatus().setStatusListCredential(LIST_URL);
        sameListAsUrl.getCredentialStatus().setStatusListIndex(2L);
        revocationRecorded(2);
        assertNotNull(service.updateCredentialStatus(sameListAsUrl));
    }

    @Test
    void v1_credentialWithoutStatusEntries_keepsItsError() {
        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.of(ledger()));
        CertifyException error = assertThrows(CertifyException.class, () -> service.updateCredentialStatus(v1("revocation", true)));
        assertEquals(ErrorConstants.MISSING_CREDENTIAL_STATUS_DETAILS, error.getErrorCode());
    }

    @Test
    void nonRevocationChange_isQueuedAsAPlainTransaction() {
        when(ledgerRepository.findByCredentialId(CREDENTIAL)).thenReturn(Optional.of(ledger(detail("suspension", 4))));
        when(transactions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.updateCredentialStatus(v1("suspension", false));

        verify(transactions).save(argThat(t -> "suspension".equals(t.getStatusPurpose()) && !t.getStatusValue()
                && t.getStatusListIndex() == 4L));
    }

    // ---- v2: by status list position ---------------------------------------------------------------

    private StatusListCredential list(String purpose, long kb) {
        StatusListCredential list = new StatusListCredential();
        list.setId(LIST);
        list.setStatusPurpose(purpose);
        list.setCapacityInKB(kb);
        return list;
    }

    private StatusListAvailableIndices position(boolean assigned, LocalDateTime issuedAt) {
        StatusListAvailableIndices position = new StatusListAvailableIndices();
        position.setIsAssigned(assigned);
        position.setIssuedAt(issuedAt);
        position.setCredentialId(CREDENTIAL);
        return position;
    }

    @Test
    void v2_revokesAnIssuedPosition() {
        when(statusLists.findById(LIST)).thenReturn(Optional.of(list("revocation", 64)));
        when(positions.findByStatusListCredentialIdAndListIndex(LIST, 9L)).thenReturn(Optional.of(position(true, LocalDateTime.now())));
        revocationRecorded(9);

        CredentialStatusResponse response = service.updateCredentialStatusV2(v2("revocation", 9, true));

        assertEquals(9L, response.getStatusListIndex());
        assertEquals("revocation", response.getStatusPurpose());
        verify(transactions).insertRevocationIfAbsent(eq(CREDENTIAL), eq(LIST), eq(9L), any());
    }

    @Test
    void v2_unknownList_is404() {
        when(statusLists.findById(LIST)).thenReturn(Optional.empty());
        ManagementApiException error = assertThrows(ManagementApiException.class, () -> service.updateCredentialStatusV2(v2("revocation", 9, true)));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
        assertEquals(ErrorConstants.STATUS_LIST_NOT_FOUND, error.getErrorCode());
    }

    @Test
    void v2_purposeOfAnotherList_isRefused() {
        when(statusLists.findById(LIST)).thenReturn(Optional.of(list("suspension", 64)));
        refusedWith(ErrorConstants.STATUS_PURPOSE_MISMATCH, () -> service.updateCredentialStatusV2(v2("revocation", 9, true)));
    }

    @Test
    void v2_indexOutsideTheList_isRefused() {
        when(statusLists.findById(LIST)).thenReturn(Optional.of(list("revocation", 64))); // 524288 positions
        refusedWith(ErrorConstants.INDEX_OUT_OF_BOUNDS, () -> service.updateCredentialStatusV2(v2("revocation", 524288, true)));
        refusedWith(ErrorConstants.INDEX_OUT_OF_BOUNDS, () -> service.updateCredentialStatusV2(v2("revocation", -1, true)));
    }

    @Test
    void v2_positionThatWasNeverIssued_isRefused() {
        when(statusLists.findById(LIST)).thenReturn(Optional.of(list("revocation", 64)));
        when(positions.findByStatusListCredentialIdAndListIndex(LIST, 9L)).thenReturn(Optional.empty());
        refusedWith(ErrorConstants.CREDENTIAL_NOT_ISSUED, () -> service.updateCredentialStatusV2(v2("revocation", 9, true)));

        when(positions.findByStatusListCredentialIdAndListIndex(LIST, 9L)).thenReturn(Optional.of(position(false, null)));
        refusedWith(ErrorConstants.CREDENTIAL_NOT_ISSUED, () -> service.updateCredentialStatusV2(v2("revocation", 9, true)));

        // Reserved but the credential was never returned (for example signing failed).
        when(positions.findByStatusListCredentialIdAndListIndex(LIST, 9L)).thenReturn(Optional.of(position(true, null)));
        refusedWith(ErrorConstants.CREDENTIAL_NOT_ISSUED, () -> service.updateCredentialStatusV2(v2("revocation", 9, true)));
        verifyNoInteractions(transactions);
    }

    @Test
    void v2_revocationCannotBeUndone() {
        when(statusLists.findById(LIST)).thenReturn(Optional.of(list("revocation", 64)));
        when(positions.findByStatusListCredentialIdAndListIndex(LIST, 9L)).thenReturn(Optional.of(position(true, LocalDateTime.now())));
        refusedWith(ErrorConstants.REVOCATION_CANNOT_BE_UNDONE, () -> service.updateCredentialStatusV2(v2("revocation", 9, false)));
    }

    @Test
    void v2_statusIdThatDiffersFromTheList_keepsItsError() {
        UpdateCredentialStatusRequestV2 request = v2("revocation", 9, true);
        request.getCredentialStatus().setId("something-else");
        CertifyException error = assertThrows(CertifyException.class, () -> service.updateCredentialStatusV2(request));
        assertEquals(ErrorConstants.STATUS_ID_MISMATCH, error.getErrorCode());
    }
}
