package io.mosip.certify.services;

import io.mosip.certify.core.constants.ErrorConstants;
import io.mosip.certify.core.dto.CredentialStatusDetail;
import io.mosip.certify.core.exception.CertifyException;
import io.mosip.certify.core.spi.CredentialLedgerService;
import io.mosip.certify.repository.StatusListAvailableIndicesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IssuanceRecordServiceTest {

    @Mock
    private CredentialLedgerService ledgerService;
    @Mock
    private StatusListAvailableIndicesRepository positionRepository;
    @InjectMocks
    private IssuanceRecordService service;

    @BeforeEach
    void init() {
        MockitoAnnotations.openMocks(this);
    }

    private static final LocalDateTime ISSUED = LocalDateTime.of(2026, 11, 9, 10, 0);
    private static final LocalDateTime EXPIRES = LocalDateTime.of(2027, 11, 9, 10, 0);

    private IssuanceRecord record(CredentialStatusDetail status, boolean ledgerEnabled) {
        return new IssuanceRecord("urn:uuid:1", "did:web:issuer", "ERLCredential,VerifiableCredential", status,
                Map.of("document_id", "42"), ISSUED, EXPIRES, ledgerEnabled);
    }

    private CredentialStatusDetail status() {
        return new CredentialStatusDetail("revocation", "list-1", 7L, 1L);
    }

    @Test
    void writesLedgerEntryWithValidityEnd_andConfirmsThePosition() {
        when(positionRepository.confirmIssued(eq("list-1"), eq(7L), eq("urn:uuid:1"), any())).thenReturn(1);

        service.record(record(status(), true));

        verify(ledgerService).storeLedgerEntry("urn:uuid:1", "did:web:issuer", "ERLCredential,VerifiableCredential",
                status(), Map.of("document_id", "42"), ISSUED, EXPIRES);
        verify(positionRepository).confirmIssued(eq("list-1"), eq(7L), eq("urn:uuid:1"), any());
    }

    @Test
    void ledgerDisabled_stillConfirmsThePosition() {
        when(positionRepository.confirmIssued(anyString(), anyLong(), any(), any())).thenReturn(1);

        service.record(record(status(), false));

        verifyNoInteractions(ledgerService);
        verify(positionRepository).confirmIssued(eq("list-1"), eq(7L), eq("urn:uuid:1"), any());
    }

    @Test
    void noStatus_onlyWritesTheLedger() {
        service.record(record(null, true));

        verify(ledgerService).storeLedgerEntry(any(), any(), any(), isNull(), any(), any(), any());
        verifyNoInteractions(positionRepository);
    }

    @Test
    void positionThatWasNeverReserved_failsTheRecord() {
        when(positionRepository.confirmIssued(anyString(), anyLong(), any(), any())).thenReturn(0);

        CertifyException error = assertThrows(CertifyException.class, () -> service.record(record(status(), true)));

        assertEquals(ErrorConstants.ISSUANCE_RECORD_FAILED, error.getErrorCode());
    }

    @Test
    void ledgerFailure_propagates_andSkipsConfirmation() {
        doThrow(new CertifyException(ErrorConstants.LEDGER_ENTRY_FAILED)).when(ledgerService)
                .storeLedgerEntry(any(), any(), any(), any(), any(), any(), any());

        assertThrows(CertifyException.class, () -> service.record(record(status(), true)));

        verifyNoInteractions(positionRepository);
    }
}
