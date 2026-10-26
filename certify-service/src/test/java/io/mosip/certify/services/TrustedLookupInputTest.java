package io.mosip.certify.services;
import java.util.*;
import io.mosip.certify.core.dto.*;
import io.mosip.certify.core.exception.InvalidRequestException;
import io.mosip.certify.api.spi.LookupDataProviderPlugin;
import org.junit.Test;
import static org.junit.Assert.*;

public class TrustedLookupInputTest {
    @Test public void bindsCredentialIdAndCopiesCachedClaims() {
        Map<String,Object> claims = new HashMap<>(Map.of("vehicle_number", "TEST", "issued_date", "2026-10-16T14:30:00"));
        PreAuthTransaction transaction = PreAuthTransaction.builder().credentialConfigurationId("ERL").claims(claims).build();
        Map<String,Object> trusted = TrustedLookupInput.fromTransaction("ERL", transaction);
        claims.put("vehicle_number", "CHANGED");
        assertEquals("TEST", ((Map<?,?>) trusted.get(LookupDataProviderPlugin.LOOKUP)).get("vehicle_number"));
        assertThrows(InvalidRequestException.class, () -> TrustedLookupInput.fromTransaction("OTHER", transaction));
        assertThrows(InvalidRequestException.class, () -> TrustedLookupInput.fromTransaction("ERL", new VCIssuanceTransaction()));
        assertThrows(InvalidRequestException.class, () -> TrustedLookupInput.fromTransaction("ERL", null));
    }
    @Test public void serializationAndNonceRefreshPreserveLookupTransaction() throws Exception {
        PreAuthTransaction transaction = PreAuthTransaction.builder().credentialConfigurationId("ERL")
            .claims(new HashMap<>(Map.of("vehicle_number", "TEST", "issued_date", "2026-10-16T14:30:00"))).build();
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        new java.io.ObjectOutputStream(bytes).writeObject(transaction);
        PreAuthTransaction restored = (PreAuthTransaction) new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())).readObject();
        VCICacheService cache = org.mockito.Mockito.mock(VCICacheService.class);
        io.mosip.certify.core.util.SecurityHelperService security = org.mockito.Mockito.mock(io.mosip.certify.core.util.SecurityHelperService.class);
        org.mockito.Mockito.when(security.generateSecureRandomString(20)).thenReturn("new-nonce");
        org.mockito.Mockito.when(cache.setVCITransaction(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        VCIssuanceTransaction refreshed = io.mosip.certify.utils.VCIssuanceUtil.createOrUpdateVCITransaction(security, 300, cache, "token-hash", restored);
        assertEquals("new-nonce", refreshed.getCNonce());
        assertEquals("ERL", ((PreAuthTransaction) refreshed).getCredentialConfigurationId());
        assertEquals(transaction.getClaims(), ((PreAuthTransaction) refreshed).getClaims());
    }

}
