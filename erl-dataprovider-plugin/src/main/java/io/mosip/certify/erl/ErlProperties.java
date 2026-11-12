package io.mosip.certify.erl;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
@ConfigurationProperties("dmt.erl")
@Validated
public class ErlProperties {
    @NotBlank private String licenceFeeCode;
    @NotBlank private String arrearsCode;
    @NotBlank private String finesCode;
    public String getLicenceFeeCode() { return licenceFeeCode; }
    public void setLicenceFeeCode(String value) { licenceFeeCode = value; }
    public String getArrearsCode() { return arrearsCode; }
    public void setArrearsCode(String value) { arrearsCode = value; }
    public String getFinesCode() { return finesCode; }
    public void setFinesCode(String value) { finesCode = value; }
}
