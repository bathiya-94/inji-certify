package io.mosip.certify.core.dto;

import java.util.Map;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.mosip.certify.core.validation.StrictIntegerDeserializer;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class CredentialLedgerSearchRequest {
    private String credentialId;

    @NotBlank(message = "issuerId is mandatory")
    private String issuerId;

    @NotBlank(message = "credentialType is mandatory")
    private String credentialType;

    private Map<String, String> indexedAttributesEquals;

    /** Page size, 1 to the configured maximum. Sending a limit or an offset selects paginated mode. */
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer limit;

    /** Number of matching records to skip, 0 to the configured maximum. */
    @JsonDeserialize(using = StrictIntegerDeserializer.class)
    private Integer offset;

    /** Whether the caller asked for pagination, decided before any defaults are applied. */
    @JsonIgnore
    public boolean isPaginationRequested() {
        return limit != null || offset != null;
    }
}