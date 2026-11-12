package io.mosip.certify.core.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

@Data
public class UpdateCredentialStatusRequestV2 {
    @NotNull
    @Valid
    private CredentialStatusDtoV2 credentialStatus;

    @NotNull
    private Boolean status;

    @Data
    public static class CredentialStatusDtoV2 {
        private String id;
        private String type;
        private String statusPurpose;
        @NotNull
        @PositiveOrZero(message = "statusListIndex must not be negative")
        private Long statusListIndex;
        @NotBlank(message = "statusListCredential must not be blank")
        private String statusListCredential;
    }
}
